package oog.mega.saguaro.mode.scoremax;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ScoreMaxScoreHistoryProfile;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.MovementController;
import oog.mega.saguaro.movement.MovementEngine;
import oog.mega.saguaro.movement.PathGenerationContext;
import oog.mega.saguaro.movement.PathIntersectionContext;
import oog.mega.saguaro.movement.PathWaveIntersection;
import oog.mega.saguaro.movement.RamEvent;
import robocode.Rules;

final class ScoreMaxPlanner {
    private static final int MAX_CARRIED_FORWARD_FAMILIES = 2;
    private static final int GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT = 6;
    private static final double DEFAULT_TRACKING_FIRE_POWER = 2.0;
    private static final double DEFAULT_PRIOR_WEIGHT_FLOOR = 0.35;
    private static final double DEFAULT_PRIOR_WEIGHT_EXPONENT = 0.5;
    private static final double DEFAULT_ENERGY_LEAD_SCALE = 8.0;
    private static final double DEFAULT_ENERGY_LEAD_OFFSET = 20.0;
    private static final double DEFAULT_LOW_ENERGY_UNCERTAINTY_SCALE = 30.0;
    private static final double DEFAULT_READY_TICK_ENERGY_EQUIVALENT = 0.35;
    private static final int MAX_READY_TICK_LEAD = 8;
    private static final int MAX_BRANCHED_SHOT_EVENTS = 4;
    private static final double MIN_CONTINUATION_POWER = 0.1;
    private static final double DEFAULT_ENEMY_CONTINUATION_POWER = 2.0;
    private static final double MIN_TERMINAL_RATE = 1e-6;
    private static final double POWER_SAMPLE_EPSILON = 1e-6;
    private static final double GOLDEN_SECTION_LOCAL_STEP = 0.3819660112501051;
    // Preserve the end-of-round win-rate EV path behind a single local toggle.
    private static final boolean ENABLE_END_OF_ROUND_BONUS_EV = true;
    private static final double MIN_PRIOR_WIN_PROBABILITY = 0.05;
    private static final double MAX_PRIOR_WIN_PROBABILITY = 0.95;
    private static final double FIRST_BATTLE_SCOREMAX_PRIOR_TARGET = 0.90;
    private static final int FIRST_BATTLE_SCOREMAX_PRIOR_ROUNDS = 10;
    private static final double EVENT_TIME_EPSILON = 1e-9;

    private Info info;
    private MovementController movement;
    private GunController gun;
    private ShotPlanner shotPlanner;
    private CandidatePath lastSelectedPath;
    private List<CandidatePath> lastSelectedFamilyPaths;
    private List<CandidatePath> lastSelectedSafeSpotPaths;
    private List<PathWaveIntersection> lastSelectedPathIntersections;
    private long lastSelectedWaveDangerRevision;
    private double lastSelectedFirePower;
    private boolean lastSelectedShotWasShadow;
    private double retainedOffensivePower;
    private double offensivePowerBracketMin;
    private double offensivePowerBracketMax;
    private boolean offensivePowerBracketValid;
    private int scoreMaxSelectedRoundCount;
    private int lastCountedScoreMaxRound;
    private ScoreContext scoreContext;

    public void init(Info info, MovementController movement, GunController gun) {
        if (info == null || movement == null || gun == null) {
            throw new IllegalArgumentException("ScoreMaxPlanner requires non-null info, movement, and gun");
        }
        this.info = info;
        this.movement = movement;
        this.gun = gun;
        this.shotPlanner = new ShotPlanner(
                info,
                movement,
                gun,
                (path, pathIntersections, selection, currentOurEnergy) -> scoreShotSelection(
                        path,
                        pathIntersections,
                        selection,
                        currentOurEnergy));
        this.lastSelectedPath = null;
        this.lastSelectedFamilyPaths = new ArrayList<>();
        this.lastSelectedSafeSpotPaths = new ArrayList<>();
        this.lastSelectedPathIntersections = new ArrayList<>();
        this.lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();
        this.lastSelectedFirePower = 0.0;
        this.lastSelectedShotWasShadow = false;
        this.retainedOffensivePower = DEFAULT_TRACKING_FIRE_POWER;
        this.offensivePowerBracketMin = Double.NaN;
        this.offensivePowerBracketMax = Double.NaN;
        this.offensivePowerBracketValid = false;
        if (info.getRobot().getRoundNum() == 0) {
            this.scoreMaxSelectedRoundCount = 0;
            this.lastCountedScoreMaxRound = Integer.MIN_VALUE;
        }
        this.scoreContext = null;
    }

    private static class FiringResult {
        private final double firstTickFirePower;
        private final double firstTickGunTurn;
        private final List<PathWaveIntersection> pathIntersections;
        private final ShotPlanner.ShotSelection shotSelection;
        private final boolean valid;

        private FiringResult(double firstTickFirePower,
                             double firstTickGunTurn,
                             List<PathWaveIntersection> pathIntersections,
                             ShotPlanner.ShotSelection shotSelection,
                             boolean valid) {
            this.firstTickFirePower = firstTickFirePower;
            this.firstTickGunTurn = firstTickGunTurn;
            this.pathIntersections = pathIntersections;
            this.shotSelection = shotSelection;
            this.valid = valid;
        }
    }

    private static final class FireWindowContext {
        private final CandidatePath path;
        private final List<PathWaveIntersection> pathIntersections;
        private final boolean active;
        private final int tickOffset;
        private final PhysicsUtil.PositionState state;
        private final EnemyInfo.PredictedPosition enemyAtFireTime;
        private final double expectedBulletDamageTaken;
        private final double expectedEnemyEnergyGain;
        private final double baseEnergyDelta;
        private final double availableEnergy;
        private final double enemyEnergyForScoring;
        private final long fireTime;

        private FireWindowContext(CandidatePath path,
                                  List<PathWaveIntersection> pathIntersections,
                                  boolean active,
                                  int tickOffset,
                                  PhysicsUtil.PositionState state,
                                  EnemyInfo.PredictedPosition enemyAtFireTime,
                                  double expectedBulletDamageTaken,
                                  double expectedEnemyEnergyGain,
                                  double baseEnergyDelta,
                                  double availableEnergy,
                                  double enemyEnergyForScoring,
                                  long fireTime) {
            this.path = path;
            this.pathIntersections = pathIntersections;
            this.active = active;
            this.tickOffset = tickOffset;
            this.state = state;
            this.enemyAtFireTime = enemyAtFireTime;
            this.expectedBulletDamageTaken = expectedBulletDamageTaken;
            this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
            this.baseEnergyDelta = baseEnergyDelta;
            this.availableEnergy = availableEnergy;
            this.enemyEnergyForScoring = enemyEnergyForScoring;
            this.fireTime = fireTime;
        }
    }

    private static final class ContinuationDeltas {
        private double expectedScoreGained;
        private double expectedScoreConceded;
        private double expectedEnemyEnergyGain;
        private double expectedEnemyEnergyDrain;
        private double energyDelta;
        private double expectedEnemyEnergyLoss;
        private double expectedOpponentCreditedDamage;

        private void addScaled(ContinuationEventEffects effects, double scale) {
            if (effects == null || !(scale > 0.0)) {
                return;
            }
            expectedScoreGained += scale * effects.expectedScoreGained;
            expectedScoreConceded += scale * effects.expectedScoreConceded;
            expectedEnemyEnergyGain += scale * effects.expectedEnemyEnergyGain;
            expectedEnemyEnergyDrain += scale * effects.expectedEnemyEnergyDrain;
            energyDelta += scale * effects.energyDelta;
            expectedEnemyEnergyLoss += scale * effects.expectedEnemyEnergyLoss;
            expectedOpponentCreditedDamage += scale * effects.expectedOpponentCreditedDamage;
        }
    }

    private static final class ContinuationEventEffects {
        private final double expectedScoreGained;
        private final double expectedScoreConceded;
        private final double expectedEnemyEnergyGain;
        private final double expectedEnemyEnergyDrain;
        private final double energyDelta;
        private final double expectedEnemyEnergyLoss;
        private final double expectedOpponentCreditedDamage;

        private ContinuationEventEffects(double expectedScoreGained,
                                         double expectedScoreConceded,
                                         double expectedEnemyEnergyGain,
                                         double expectedEnemyEnergyDrain,
                                         double energyDelta,
                                         double expectedEnemyEnergyLoss,
                                         double expectedOpponentCreditedDamage) {
            this.expectedScoreGained = expectedScoreGained;
            this.expectedScoreConceded = expectedScoreConceded;
            this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
            this.expectedEnemyEnergyDrain = expectedEnemyEnergyDrain;
            this.energyDelta = energyDelta;
            this.expectedEnemyEnergyLoss = expectedEnemyEnergyLoss;
            this.expectedOpponentCreditedDamage = expectedOpponentCreditedDamage;
        }
    }

    private static final class TailShotCredit {
        private final boolean ourSide;
        private final double shotPower;
        private final double hitProbability;
        private final boolean deductEnergyCost;
        private final boolean requireShooterAlive;
        private final double shotWeight;

        private TailShotCredit(boolean ourSide,
                               double shotPower,
                               double hitProbability,
                               boolean deductEnergyCost,
                               boolean requireShooterAlive,
                               double shotWeight) {
            this.ourSide = ourSide;
            this.shotPower = shotPower;
            this.hitProbability = hitProbability;
            this.deductEnergyCost = deductEnergyCost;
            this.requireShooterAlive = requireShooterAlive;
            this.shotWeight = shotWeight;
        }
    }

    private static final class EnemyActualShot {
        private final double eventTime;
        private final long fireTime;
        private final double shotPower;
        private final double hitProbability;
        private final Wave wave;

        private EnemyActualShot(double eventTime,
                                long fireTime,
                                double shotPower,
                                double hitProbability,
                                Wave wave) {
            this.eventTime = eventTime;
            this.fireTime = fireTime;
            this.shotPower = shotPower;
            this.hitProbability = hitProbability;
            this.wave = wave;
        }
    }

    private static final class EnemyContinuationPlan {
        private final List<EnemyActualShot> actualShots;
        private final double templatePower;
        private final double templateHitProbability;
        private final double templateCadenceTicks;
        private final double continuationWindowStartTime;
        private final double firstContinuationCadenceTicks;

        private EnemyContinuationPlan(List<EnemyActualShot> actualShots,
                                      double templatePower,
                                      double templateHitProbability,
                                      double templateCadenceTicks,
                                      double continuationWindowStartTime,
                                      double firstContinuationCadenceTicks) {
            this.actualShots = actualShots;
            this.templatePower = templatePower;
            this.templateHitProbability = templateHitProbability;
            this.templateCadenceTicks = templateCadenceTicks;
            this.continuationWindowStartTime = continuationWindowStartTime;
            this.firstContinuationCadenceTicks = firstContinuationCadenceTicks;
        }
    }

    private static final class BranchedEventSchedule {
        private final List<ImmediateEvent> events;
        private final List<TailShotCredit> tailShots;

        private BranchedEventSchedule(List<ImmediateEvent> events,
                                      List<TailShotCredit> tailShots) {
            this.events = events;
            this.tailShots = tailShots;
        }
    }

    private static class PlanningBranch {
        private final MovementEngine.PlannedShot forcedShot;
        private final double offensivePower;
        private final boolean allowShadow;
        private final boolean allowOffensive;
        private final boolean skipNoFire;

        private PlanningBranch(MovementEngine.PlannedShot forcedShot,
                               double offensivePower,
                               boolean allowShadow,
                               boolean allowOffensive,
                               boolean skipNoFire) {
            this.forcedShot = forcedShot;
            this.offensivePower = offensivePower;
            this.allowShadow = allowShadow;
            this.allowOffensive = allowOffensive;
            this.skipNoFire = skipNoFire;
        }
    }

    private static class ScoreContext {
        private final double ourScore;
        private final double opponentScore;
        private final boolean bonusEnabled;
        private final double currentEnemyEnergy;
        private final double currentWinProbability;
        private final double currentOurTerminalAdd;
        private final double currentOpponentTerminalAdd;

        private ScoreContext(double ourScore,
                             double opponentScore,
                             boolean bonusEnabled,
                             double currentEnemyEnergy,
                             double currentWinProbability,
                             double currentOurTerminalAdd,
                             double currentOpponentTerminalAdd) {
            this.ourScore = ourScore;
            this.opponentScore = opponentScore;
            this.bonusEnabled = bonusEnabled;
            this.currentEnemyEnergy = currentEnemyEnergy;
            this.currentWinProbability = currentWinProbability;
            this.currentOurTerminalAdd = currentOurTerminalAdd;
            this.currentOpponentTerminalAdd = currentOpponentTerminalAdd;
        }
    }

    private static final class TerminalScoreEstimate {
        final double ourTerminalAdd;
        final double opponentTerminalAdd;

        private TerminalScoreEstimate(double ourTerminalAdd, double opponentTerminalAdd) {
            this.ourTerminalAdd = ourTerminalAdd;
            this.opponentTerminalAdd = opponentTerminalAdd;
        }
    }

    private static class PlanEvaluation {
        private final BattlePlan plan;
        private final CandidatePath path;
        private final List<PathWaveIntersection> pathIntersections;
        private final double score;
        private final double absoluteScore;
        private final boolean selectedShotWasShadow;

        private PlanEvaluation(BattlePlan plan,
                               CandidatePath path,
                               List<PathWaveIntersection> pathIntersections,
                               double score,
                               double absoluteScore,
                               boolean selectedShotWasShadow) {
            this.plan = plan;
            this.path = path;
            this.pathIntersections = pathIntersections;
            this.score = score;
            this.absoluteScore = absoluteScore;
            this.selectedShotWasShadow = selectedShotWasShadow;
        }
    }

    private static class PlanSelection {
        private BattlePlan bestPlan;
        private CandidatePath bestPath;
        private List<PathWaveIntersection> bestPathIntersections = new ArrayList<>();
        private double bestScore = Double.NEGATIVE_INFINITY;
        private double bestAbsoluteScore = Double.NEGATIVE_INFINITY;
        private boolean bestShotWasShadow;
        private PlanEvaluation bestEvaluation;

        private void consider(PlanEvaluation evaluation) {
            if (evaluation == null) {
                return;
            }
            if (isBetterScore(evaluation.score, evaluation.absoluteScore, bestScore, bestAbsoluteScore)) {
                bestScore = evaluation.score;
                bestAbsoluteScore = evaluation.absoluteScore;
                bestPlan = evaluation.plan;
                bestPath = evaluation.path;
                bestPathIntersections = evaluation.pathIntersections;
                bestShotWasShadow = evaluation.selectedShotWasShadow;
                bestEvaluation = evaluation;
            }
        }
    }

    private enum FirstPassShotMode {
        OFFENSIVE,
        NO_FIRE,
        SHADOW
    }

    private enum ImmediateEventKind {
        OUR_SHOT,
        ENEMY_SHOT,
        WALL_HIT,
        RAM
    }

    private static final class ImmediateEvent {
        private final double time;
        private final int priority;
        private final ImmediateEventKind kind;
        private final double value;
        private final double probability;
        private final double shotPower;
        private final Wave wave;
        private final RamEvent ramEvent;
        private final boolean deductEnergyCost;
        private final boolean requireShooterAlive;

        private ImmediateEvent(double time,
                               int priority,
                               ImmediateEventKind kind,
                               double value,
                               double probability,
                               double shotPower,
                               Wave wave,
                               RamEvent ramEvent,
                               boolean deductEnergyCost,
                               boolean requireShooterAlive) {
            this.time = time;
            this.priority = priority;
            this.kind = kind;
            this.value = value;
            this.probability = probability;
            this.shotPower = shotPower;
            this.wave = wave;
            this.ramEvent = ramEvent;
            this.deductEnergyCost = deductEnergyCost;
            this.requireShooterAlive = requireShooterAlive;
        }
    }

    private static final class PlanOutcomeBranch {
        private double probability;
        private double ourScore;
        private double opponentScore;
        private double ourEnergy;
        private double enemyEnergy;
        private double ourCreditedDamageOnEnemy;
        private double enemyCreditedDamageOnUs;
        private boolean ourKillBonusApplied;
        private boolean enemyKillBonusApplied;
        private boolean ourAlive;
        private boolean enemyAlive;
        private boolean roundResolved;

        private PlanOutcomeBranch(double probability,
                                  double ourScore,
                                  double opponentScore,
                                  double ourEnergy,
                                  double enemyEnergy,
                                  double ourCreditedDamageOnEnemy,
                                  double enemyCreditedDamageOnUs,
                                  boolean ourKillBonusApplied,
                                  boolean enemyKillBonusApplied,
                                  boolean ourAlive,
                                  boolean enemyAlive,
                                  boolean roundResolved) {
            this.probability = probability;
            this.ourScore = ourScore;
            this.opponentScore = opponentScore;
            this.ourEnergy = ourEnergy;
            this.enemyEnergy = enemyEnergy;
            this.ourCreditedDamageOnEnemy = ourCreditedDamageOnEnemy;
            this.enemyCreditedDamageOnUs = enemyCreditedDamageOnUs;
            this.ourKillBonusApplied = ourKillBonusApplied;
            this.enemyKillBonusApplied = enemyKillBonusApplied;
            this.ourAlive = ourAlive;
            this.enemyAlive = enemyAlive;
            this.roundResolved = roundResolved;
        }

        private PlanOutcomeBranch copyWithProbability(double probability) {
            return new PlanOutcomeBranch(
                    probability,
                    ourScore,
                    opponentScore,
                    ourEnergy,
                    enemyEnergy,
                    ourCreditedDamageOnEnemy,
                    enemyCreditedDamageOnUs,
                    ourKillBonusApplied,
                    enemyKillBonusApplied,
                    ourAlive,
                    enemyAlive,
                    roundResolved);
        }
    }

    private static final class BranchValue {
        private final double value;
        private final double absoluteScore;

        private BranchValue(double value, double absoluteScore) {
            this.value = value;
            this.absoluteScore = absoluteScore;
        }
    }

    private static final class BranchAggregation {
        private double weightedValue;
        private double weightedAbsoluteScore;
        private double settledProbability;
    }

    private double optimalGunTurnFromHeading(double firePower, double gunHeading) {
        double optimalAngle = gun.getOptimalFiringAngle(firePower);
        if (Double.isNaN(optimalAngle)) {
            return 0.0;
        }
        return MathUtils.normalizeAngle(optimalAngle - gunHeading);
    }

    private FireWindowContext buildFireWindowContext(CandidatePath path,
                                                     int firstFiringTickOffset,
                                                     double currentOurEnergy,
                                                     PathIntersectionContext pathIntersectionContext) {
        List<PathWaveIntersection> pathIntersections = path.pathIntersections;
        if (pathIntersections == null) {
            pathIntersections = movement.collectPathWaveIntersections(path, pathIntersectionContext);
        }
        int pathLength = path.trajectory.length();
        if (firstFiringTickOffset < 0 || firstFiringTickOffset >= pathLength) {
            return new FireWindowContext(
                    path,
                    pathIntersections,
                    false,
                    firstFiringTickOffset,
                    null,
                    null,
                    0.0,
                    0.0,
                    0.0,
                    currentOurEnergy,
                    currentEnemyEnergyForScoring(),
                    path.startTime);
        }

        PhysicsUtil.PositionState state = path.trajectory.stateAt(firstFiringTickOffset);
        double expectedBulletDamageTaken = path.totalDanger;
        double expectedEnemyEnergyGain = path.expectedEnemyEnergyGain;
        double baseEnergyDelta = -(expectedBulletDamageTaken + path.wallHitDamage + path.ramEnergyLoss);
        long fireTime = path.startTime + firstFiringTickOffset;
        return new FireWindowContext(
                path,
                pathIntersections,
                true,
                firstFiringTickOffset,
                state,
                predictEnemyPositionAt(fireTime),
                expectedBulletDamageTaken,
                expectedEnemyEnergyGain,
                baseEnergyDelta,
                currentOurEnergy,
                currentEnemyEnergyForScoring(),
                fireTime);
    }

    private FiringResult evaluateFiringAlongPath(CandidatePath path,
                                                 int firstFiringTickOffset,
                                                 PlanningBranch branch,
                                                 double currentGunHeading,
                                                 double gunCoolingRate,
                                                 double currentOurEnergy,
                                                 PathIntersectionContext pathIntersectionContext,
                                                 FireWindowContext fireWindowContext) {
        PlanningBranch activeBranch = branch != null ? branch : createOffensivePowerBranch(DEFAULT_TRACKING_FIRE_POWER);
        FireWindowContext activeFireWindow = fireWindowContext != null && fireWindowContext.path == path
                ? fireWindowContext
                : buildFireWindowContext(path, firstFiringTickOffset, currentOurEnergy, pathIntersectionContext);
        List<PathWaveIntersection> pathIntersections = activeFireWindow.pathIntersections;
        double firstTickFirePower = 0;

        double firstWindowPower = 0;
        double firstWindowFiringAngle = Double.NaN;
        PhysicsUtil.PositionState firstWindowState = null;
        EnemyInfo.PredictedPosition firstWindowEnemyAtFireTime = null;
        ShotPlanner.ShotSelection selectedShot = new ShotPlanner.ShotSelection(
                0.0,
                Double.NaN,
                0.0,
                path.startTime,
                path.startTime,
                0.0,
                0.0,
                path.totalDanger,
                path.expectedEnemyEnergyGain,
                0.0,
                Double.NaN,
                Double.NaN,
                0.0,
                false,
                null);
        boolean valid = true;

        if (activeFireWindow.active) {
            PhysicsUtil.PositionState state = activeFireWindow.state;
            firstWindowState = state;
            firstWindowEnemyAtFireTime = activeFireWindow.enemyAtFireTime;

            // NOTE: Power selection currently happens after movement path generation.
            // Outgoing bullet-shadow survival is modeled here, but path danger
            // itself is still based on the precomputed movement evaluation.
            ShotPlanner.ShotSelection shotSelection = shotPlanner.chooseBestShot(
                    path,
                    state.x, state.y, state.heading, state.velocity, activeFireWindow.enemyAtFireTime,
                    activeFireWindow.expectedBulletDamageTaken, activeFireWindow.expectedEnemyEnergyGain,
                    activeFireWindow.baseEnergyDelta, activeFireWindow.availableEnergy, currentGunHeading, gunCoolingRate,
                    currentOurEnergy, activeFireWindow.tickOffset, activeFireWindow.fireTime,
                    pathIntersections,
                    activeBranch.forcedShot,
                    activeFireWindow.enemyEnergyForScoring,
                    activeBranch.offensivePower,
                    activeBranch.allowShadow,
                    activeBranch.allowOffensive,
                    activeBranch.skipNoFire);
            selectedShot = shotSelection;

            if (activeBranch.forcedShot != null && shotSelection.power < ShotPlanner.MIN_FIRE_POWER) {
                valid = false;
            }

            if (shotSelection.power > 0) {
                if (activeFireWindow.tickOffset == 0) {
                    firstTickFirePower = shotSelection.power;
                }

                firstWindowPower = shotSelection.power;
                firstWindowFiringAngle = shotSelection.firingAngle;
            }
        }

        if (valid && !selectedShot.hasFiniteScore()) {
            selectedShot = selectedShot.withScore(
                    scoreShotSelection(path, pathIntersections, selectedShot, currentOurEnergy));
        }

        // Even when not firing, keep the gun tracking the best reachable shot
        // from the same future firing state we just evaluated for this path.
        double aimPower = firstWindowPower > 0 ? firstWindowPower : DEFAULT_TRACKING_FIRE_POWER;
        double firstTickGunTurn;
        if (Double.isFinite(firstWindowFiringAngle)) {
            firstTickGunTurn = MathUtils.normalizeAngle(firstWindowFiringAngle - currentGunHeading);
        } else {
            ShotSolution trackingShot = firstWindowEnemyAtFireTime != null && firstWindowState != null
                    ? gun.selectOptimalUnconstrainedShotFromPosition(
                            firstWindowState.x,
                            firstWindowState.y,
                            firstWindowEnemyAtFireTime.x,
                            firstWindowEnemyAtFireTime.y,
                            aimPower,
                            0)
                    : new ShotSolution(0.0, Double.NaN);
            firstTickGunTurn = Double.isFinite(trackingShot.firingAngle)
                    ? MathUtils.normalizeAngle(trackingShot.firingAngle - currentGunHeading)
                    : optimalGunTurnFromHeading(aimPower, currentGunHeading);
        }

        return new FiringResult(
                firstTickFirePower,
                firstTickGunTurn,
                pathIntersections,
                selectedShot,
                valid);
    }

    private static int firstFiringTickOffset(double currentGunHeat) {
        if (currentGunHeat < 0.001) {
            return 0;
        }
        return (int) Math.floor((currentGunHeat - 0.001) / 0.1) + 1;
    }

    private ContinuationDeltas estimateBoundedContinuationDeltas(BranchedEventSchedule schedule,
                                                                 PlanOutcomeBranch branch) {
        ContinuationDeltas deltas = new ContinuationDeltas();
        if (schedule == null || branch == null || !branch.ourAlive || !branch.enemyAlive || branch.roundResolved) {
            return deltas;
        }
        for (TailShotCredit tailShot : schedule.tailShots) {
            addTailShotCredit(deltas, tailShot, branch);
        }
        return deltas;
    }

    private static double boundedContinuationHorizonTime(CandidatePath path) {
        return lastEnemyWavePassTime(path);
    }

    private BranchedEventSchedule buildBranchedEventSchedule(CandidatePath path,
                                                             List<PathWaveIntersection> pathIntersections,
                                                             ShotPlanner.ShotSelection selection) {
        List<ImmediateEvent> shotEvents = new ArrayList<>();
        List<TailShotCredit> tailShots = new ArrayList<>();
        double horizonTime = boundedContinuationHorizonTime(path);
        addOurShotEvents(shotEvents, tailShots, selection, horizonTime);
        EnemyContinuationPlan enemyPlan = buildEnemyContinuationPlan(pathIntersections, selection);
        addEnemyShotEvents(shotEvents, tailShots, enemyPlan, horizonTime);
        trimBranchedShotEvents(shotEvents, tailShots);

        List<ImmediateEvent> events = new ArrayList<>(shotEvents);
        addWallHitEvents(events, path);
        addRamEvents(events, path);
        sortImmediateEvents(events);
        return new BranchedEventSchedule(events, tailShots);
    }

    private void trimBranchedShotEvents(List<ImmediateEvent> shotEvents,
                                        List<TailShotCredit> tailShots) {
        if (shotEvents == null || shotEvents.size() <= MAX_BRANCHED_SHOT_EVENTS) {
            return;
        }
        sortImmediateEvents(shotEvents);
        for (int i = MAX_BRANCHED_SHOT_EVENTS; i < shotEvents.size(); i++) {
            ImmediateEvent event = shotEvents.get(i);
            if (event == null) {
                continue;
            }
            appendTailShotCredit(
                    tailShots,
                    event.kind == ImmediateEventKind.OUR_SHOT,
                    event.shotPower,
                    event.probability,
                    event.deductEnergyCost,
                    event.requireShooterAlive,
                    1.0);
        }
        shotEvents.subList(MAX_BRANCHED_SHOT_EVENTS, shotEvents.size()).clear();
    }

    private static void sortImmediateEvents(List<ImmediateEvent> events) {
        if (events == null || events.size() <= 1) {
            return;
        }
        events.sort((a, b) -> {
            int timeCompare = Double.compare(a.time, b.time);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Integer.compare(a.priority, b.priority);
        });
    }

    private void addOurShotEvents(List<ImmediateEvent> events,
                                  List<TailShotCredit> tailShots,
                                  ShotPlanner.ShotSelection selection,
                                  double horizonTime) {
        if (selection == null || !(selection.power >= ShotPlanner.MIN_FIRE_POWER)) {
            return;
        }
        int explicitShotCount = 0;
        events.add(new ImmediateEvent(
                selection.fireTime,
                0,
                ImmediateEventKind.OUR_SHOT,
                0.0,
                selection.hitProbability,
                selection.power,
                null,
                null,
                true,
                true));
        explicitShotCount++;
        if (!Double.isFinite(horizonTime)) {
            return;
        }
        double cadenceTicks = continuationCadenceTicks(selection.power);
        if (!(cadenceTicks > 0.0)) {
            return;
        }
        double windowStartTime = selection.fireTime;
        double nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON
                && explicitShotCount < MAX_BRANCHED_SHOT_EVENTS) {
            events.add(new ImmediateEvent(
                    nextShotTime,
                    0,
                    ImmediateEventKind.OUR_SHOT,
                    0.0,
                    selection.hitProbability,
                    selection.power,
                    null,
                    null,
                    true,
                    true));
            explicitShotCount++;
            windowStartTime = nextShotTime;
            nextShotTime += cadenceTicks;
        }
        appendTailShotCredit(
                tailShots,
                true,
                selection.power,
                selection.hitProbability,
                true,
                true,
                tailShotWeight(windowStartTime, cadenceTicks, cadenceTicks, horizonTime));
    }

    private static void addWallHitEvents(List<ImmediateEvent> events,
                                         CandidatePath path) {
        if (path == null || path.trajectory == null) {
            return;
        }
        for (int i = 1; i < path.trajectory.length(); i++) {
            PhysicsUtil.PositionState state = path.trajectory.stateAt(i);
            if (state.wallHitDamage > 0.0) {
                events.add(new ImmediateEvent(
                        path.startTime + i,
                        3,
                        ImmediateEventKind.WALL_HIT,
                        state.wallHitDamage,
                        1.0,
                        0.0,
                        null,
                        null,
                        false,
                        false));
            }
        }
    }

    private static void addRamEvents(List<ImmediateEvent> events,
                                     CandidatePath path) {
        if (path == null || path.ramEvents == null) {
            return;
        }
        for (RamEvent ramEvent : path.ramEvents) {
            if (ramEvent == null) {
                continue;
            }
            events.add(new ImmediateEvent(
                    ramEvent.time,
                    2,
                    ImmediateEventKind.RAM,
                    0.0,
                    1.0,
                    0.0,
                    null,
                    ramEvent,
                    false,
                    false));
        }
    }

    private EnemyContinuationPlan buildEnemyContinuationPlan(List<PathWaveIntersection> pathIntersections,
                                                             ShotPlanner.ShotSelection shotSelection) {
        if (pathIntersections == null || pathIntersections.isEmpty()) {
            return null;
        }
        List<EnemyActualShot> actualShots = new ArrayList<>();
        for (PathWaveIntersection intersection : pathIntersections) {
            if (intersection == null || intersection.wave == null || !intersection.wave.isEnemy) {
                continue;
            }
            actualShots.add(new EnemyActualShot(
                    intersection.firstContactTime,
                    intersection.wave.fireTime,
                    intersection.wave.getFirepower(),
                    waveHitProbability(shotSelection, intersection),
                    intersection.wave));
        }
        if (actualShots.isEmpty()) {
            return null;
        }
        actualShots.sort((a, b) -> {
            int timeCompare = Double.compare(a.eventTime, b.eventTime);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Long.compare(a.wave.fireTime, b.wave.fireTime);
        });
        EnemyActualShot firstShot = actualShots.get(0);
        double templateCadenceTicks = continuationCadenceTicks(firstShot.shotPower);
        double continuationWindowStartTime = firstShot.eventTime;
        double firstContinuationCadenceTicks = templateCadenceTicks;
        if (actualShots.size() >= 2) {
            EnemyActualShot secondShot = actualShots.get(1);
            double observedCadenceTicks = secondShot.wave.fireTime > firstShot.wave.fireTime
                    ? secondShot.wave.fireTime - firstShot.wave.fireTime
                    : Double.NaN;
            continuationWindowStartTime = secondShot.eventTime;
            firstContinuationCadenceTicks = observedCadenceTicks > 0.0
                    ? observedCadenceTicks
                    : templateCadenceTicks;
        }
        return new EnemyContinuationPlan(
                actualShots,
                firstShot.shotPower,
                firstShot.hitProbability,
                templateCadenceTicks,
                continuationWindowStartTime,
                firstContinuationCadenceTicks);
    }

    private void addEnemyShotEvents(List<ImmediateEvent> events,
                                    List<TailShotCredit> tailShots,
                                    EnemyContinuationPlan plan,
                                    double horizonTime) {
        if (plan == null) {
            return;
        }
        int explicitShotCount = 0;
        int actualShotIndex = 0;
        for (; actualShotIndex < plan.actualShots.size()
                && explicitShotCount < MAX_BRANCHED_SHOT_EVENTS;
             actualShotIndex++) {
            EnemyActualShot actualShot = plan.actualShots.get(actualShotIndex);
            events.add(new ImmediateEvent(
                    actualShot.eventTime,
                    1,
                    ImmediateEventKind.ENEMY_SHOT,
                    0.0,
                    actualShot.hitProbability,
                    actualShot.shotPower,
                    actualShot.wave,
                    null,
                    false,
                    false));
            explicitShotCount++;
        }
        if (actualShotIndex < plan.actualShots.size()) {
            for (int i = actualShotIndex; i < plan.actualShots.size(); i++) {
                EnemyActualShot actualShot = plan.actualShots.get(i);
                appendTailShotCredit(
                        tailShots,
                        false,
                        actualShot.shotPower,
                        actualShot.hitProbability,
                        false,
                        false,
                        1.0);
            }
            EnemyActualShot latestActualShot = latestActualShotByFireTime(plan.actualShots);
            if (latestActualShot == null) {
                return;
            }
            appendTailShotCredit(
                    tailShots,
                    false,
                    plan.templatePower,
                    plan.templateHitProbability,
                    true,
                    true,
                    tailShotWeight(
                            latestActualShot.fireTime,
                            continuationCadenceTicks(latestActualShot.shotPower),
                            plan.templateCadenceTicks,
                            horizonTime));
            return;
        }
        if (!Double.isFinite(horizonTime)
                || !(plan.templateCadenceTicks > 0.0)
                || !(plan.firstContinuationCadenceTicks > 0.0)) {
            return;
        }
        double windowStartTime = plan.continuationWindowStartTime;
        double cadenceTicks = plan.firstContinuationCadenceTicks;
        double nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON
                && explicitShotCount < MAX_BRANCHED_SHOT_EVENTS) {
            events.add(new ImmediateEvent(
                    nextShotTime,
                    1,
                    ImmediateEventKind.ENEMY_SHOT,
                    0.0,
                    plan.templateHitProbability,
                    plan.templatePower,
                    null,
                    null,
                    true,
                    true));
            explicitShotCount++;
            windowStartTime = nextShotTime;
            cadenceTicks = plan.templateCadenceTicks;
            nextShotTime = windowStartTime + cadenceTicks;
        }
        appendTailShotCredit(
                tailShots,
                false,
                plan.templatePower,
                plan.templateHitProbability,
                true,
                true,
                tailShotWeight(windowStartTime, cadenceTicks, plan.templateCadenceTicks, horizonTime));
    }

    private static double waveHitProbability(ShotPlanner.ShotSelection shotSelection,
                                             PathWaveIntersection intersection) {
        if (intersection == null || intersection.wave == null) {
            return 0.0;
        }
        if (shotSelection != null && shotSelection.enemyWaveHitProbabilities != null) {
            Double hitProbability = shotSelection.enemyWaveHitProbabilities.get(intersection.wave);
            if (hitProbability != null) {
                return Math.max(0.0, Math.min(1.0, hitProbability.doubleValue()));
            }
        }
        if (intersection.distribution == null
                || intersection.exposedGfIntervals == null
                || intersection.exposedGfIntervals.isEmpty()) {
            return 0.0;
        }
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        BulletShadowUtil.integrateWeightedIntervals(
                                intersection.distribution,
                                intersection.exposedGfIntervals,
                                -1.0,
                                1.0)));
    }

    private static double lastEnemyWavePassTime(CandidatePath path) {
        if (path == null || path.scoringWaves == null || path.scoringWaves.isEmpty()) {
            return Double.NaN;
        }
        double lastPassTime = Double.NEGATIVE_INFINITY;
        for (Wave wave : path.scoringWaves) {
            if (wave == null || !wave.isEnemy) {
                continue;
            }
            double passTime = explicitWavePassTime(path, wave);
            if (Double.isFinite(passTime) && passTime > lastPassTime) {
                lastPassTime = passTime;
            }
        }
        return lastPassTime;
    }

    private static double explicitWavePassTime(CandidatePath path, Wave wave) {
        PhysicsUtil.PositionState[] states = path.trajectory.states;
        for (int i = 0; i < states.length; i++) {
            long time = path.startTime + i;
            PhysicsUtil.PositionState state = states[i];
            if (wave.hasPassed(state.x, state.y, time)) {
                return time;
            }
        }
        PhysicsUtil.PositionState lastState = states[states.length - 1];
        double maxDistance = wave.maxDistanceToBody(lastState.x, lastState.y);
        return Math.max(
                path.startTime + states.length - 1,
                Math.floor(maxDistance / wave.speed + wave.fireTime) + 1.0);
    }

    private void addTailShotCredit(ContinuationDeltas deltas,
                                   TailShotCredit tailShot,
                                   PlanOutcomeBranch branch) {
        if (deltas == null || tailShot == null || branch == null) {
            return;
        }
        if (!(tailShot.shotWeight > 0.0)) {
            return;
        }
        ContinuationEventEffects effects = tailShot.ourSide
                ? buildOurContinuationEventEffects(tailShot, branch.ourEnergy, branch.enemyEnergy)
                : buildEnemyContinuationEventEffects(tailShot, branch.ourEnergy, branch.enemyEnergy);
        if (effects == null) {
            return;
        }
        deltas.addScaled(effects, tailShot.shotWeight);
    }

    private ContinuationEventEffects buildOurContinuationEventEffects(TailShotCredit tailShot,
                                                                      double ourEnergy,
                                                                      double enemyEnergy) {
        if (tailShot == null
                || !(tailShot.shotPower >= MIN_CONTINUATION_POWER)
                || (tailShot.requireShooterAlive && !(ourEnergy > 0.0))
                || (tailShot.deductEnergyCost && ourEnergy + 1e-9 < tailShot.shotPower)
                || !(enemyEnergy > 0.0)) {
            return null;
        }
        double hitProbability = Math.max(0.0, Math.min(1.0, tailShot.hitProbability));
        double scoreGain = hitProbability * Math.min(Rules.getBulletDamage(tailShot.shotPower), enemyEnergy);
        double ourEnergyDelta = (tailShot.deductEnergyCost ? -tailShot.shotPower : 0.0)
                + hitProbability * 3.0 * tailShot.shotPower;
        return new ContinuationEventEffects(
                scoreGain,
                0.0,
                0.0,
                0.0,
                ourEnergyDelta,
                scoreGain,
                0.0);
    }

    private ContinuationEventEffects buildEnemyContinuationEventEffects(TailShotCredit tailShot,
                                                                        double ourEnergy,
                                                                        double enemyEnergy) {
        if (tailShot == null
                || !(tailShot.shotPower >= MIN_CONTINUATION_POWER)
                || (tailShot.requireShooterAlive && !(enemyEnergy > 0.0))
                || (tailShot.deductEnergyCost && enemyEnergy + 1e-9 < tailShot.shotPower)
                || !(ourEnergy > 0.0)) {
            return null;
        }
        double hitProbability = Math.max(0.0, Math.min(1.0, tailShot.hitProbability));
        double scoreTaken = hitProbability * Math.min(Rules.getBulletDamage(tailShot.shotPower), ourEnergy);
        double expectedEnemyEnergyGain = hitProbability * 3.0 * tailShot.shotPower;
        return new ContinuationEventEffects(
                0.0,
                scoreTaken,
                expectedEnemyEnergyGain,
                tailShot.deductEnergyCost ? tailShot.shotPower : 0.0,
                -scoreTaken,
                0.0,
                scoreTaken);
    }

    private static double overlapFraction(double windowStartTime,
                                          double cadenceTicks,
                                          double horizonTime) {
        if (!(cadenceTicks > 0.0) || !(horizonTime > windowStartTime)) {
            return 0.0;
        }
        double overlapStart = windowStartTime;
        double overlapEnd = Math.min(windowStartTime + cadenceTicks, horizonTime);
        if (!(overlapEnd > overlapStart)) {
            return 0.0;
        }
        return (overlapEnd - overlapStart) / cadenceTicks;
    }

    private static void appendTailShotCredit(List<TailShotCredit> tailShots,
                                             boolean ourSide,
                                             double shotPower,
                                             double hitProbability,
                                             boolean deductEnergyCost,
                                             boolean requireShooterAlive,
                                             double shotWeight) {
        if (tailShots == null || !(shotWeight > 0.0)) {
            return;
        }
        tailShots.add(new TailShotCredit(
                ourSide,
                shotPower,
                hitProbability,
                deductEnergyCost,
                requireShooterAlive,
                shotWeight));
    }

    private static EnemyActualShot latestActualShotByFireTime(List<EnemyActualShot> actualShots) {
        EnemyActualShot latestShot = null;
        for (EnemyActualShot actualShot : actualShots) {
            if (actualShot == null) {
                continue;
            }
            if (latestShot == null || actualShot.fireTime > latestShot.fireTime) {
                latestShot = actualShot;
            }
        }
        return latestShot;
    }

    private static double tailShotWeight(double windowStartTime,
                                         double firstCadenceTicks,
                                         double steadyCadenceTicks,
                                         double horizonTime) {
        if (!(firstCadenceTicks > 0.0)
                || !(steadyCadenceTicks > 0.0)
                || !(horizonTime > windowStartTime)) {
            return 0.0;
        }
        double shotWeight = 0.0;
        double cadenceTicks = firstCadenceTicks;
        double nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON) {
            shotWeight += 1.0;
            windowStartTime = nextShotTime;
            cadenceTicks = steadyCadenceTicks;
            nextShotTime = windowStartTime + cadenceTicks;
        }
        return shotWeight + overlapFraction(windowStartTime, cadenceTicks, horizonTime);
    }

    private ShotPlanner.ShotScore scoreShotSelection(CandidatePath path,
                                                     List<PathWaveIntersection> pathIntersections,
                                                     ShotPlanner.ShotSelection selection,
                                                     double currentOurEnergy) {
        return evaluateBranchedPlanScore(path, pathIntersections, selection, currentOurEnergy);
    }

    private ShotPlanner.ShotScore evaluateBranchedPlanScore(CandidatePath path,
                                                            List<PathWaveIntersection> pathIntersections,
                                                            ShotPlanner.ShotSelection selection,
                                                            double currentOurEnergy) {
        if (path == null || selection == null) {
            return new ShotPlanner.ShotScore(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        }
        double currentEnemyEnergy = currentEnemyEnergyForTerminalScoring(scoreContext);
        PlanOutcomeBranch initialBranch = new PlanOutcomeBranch(
                1.0,
                scoreContext != null ? scoreContext.ourScore : info.getOurScore(),
                scoreContext != null ? scoreContext.opponentScore : info.getOpponentScore(),
                Math.max(0.0, currentOurEnergy),
                Math.max(0.0, currentEnemyEnergy),
                Math.max(0.0, info.getOurCreditedDamageOnEnemyThisRound()),
                Math.max(0.0, info.getEnemyCreditedDamageOnUsThisRound()),
                false,
                false,
                currentOurEnergy > 0.0,
                currentEnemyEnergy > 0.0,
                false);
        BranchedEventSchedule schedule = buildBranchedEventSchedule(path, pathIntersections, selection);
        List<PlanOutcomeBranch> branches = new ArrayList<>();
        List<PlanOutcomeBranch> nextBranches = new ArrayList<>();
        List<PlanOutcomeBranch> activeBranches = new ArrayList<>();
        branches.add(initialBranch);
        BranchAggregation aggregation = new BranchAggregation();
        double currentEventTime = Double.NaN;

        for (ImmediateEvent event : schedule.events) {
            if (Double.isFinite(currentEventTime) && !sameEventTime(event.time, currentEventTime)) {
                activeBranches.clear();
                settleResolvedBranches(branches, activeBranches, aggregation);
                branches.clear();
                branches.addAll(activeBranches);
                if (branches.isEmpty()) {
                    if (aggregation.settledProbability > 0.0) {
                        break;
                    }
                    return new ShotPlanner.ShotScore(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
                }
            }
            currentEventTime = event.time;
            nextBranches.clear();
            for (PlanOutcomeBranch branch : branches) {
                applyImmediateEvent(nextBranches, branch, event);
            }
            branches.clear();
            for (PlanOutcomeBranch branch : nextBranches) {
                if (branch == null || !(branch.probability > 0.0)) {
                    continue;
                }
                branches.add(branch);
            }
            if (branches.isEmpty()) {
                if (aggregation.settledProbability > 0.0) {
                    break;
                }
                return new ShotPlanner.ShotScore(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
            }
        }

        activeBranches.clear();
        settleResolvedBranches(branches, activeBranches, aggregation);
        branches.clear();
        branches.addAll(activeBranches);

        for (PlanOutcomeBranch branch : branches) {
            BranchValue branchValue = evaluateBranchValue(selection, schedule, branch);
            aggregation.weightedValue += branch.probability * branchValue.value;
            aggregation.weightedAbsoluteScore += branch.probability * branchValue.absoluteScore;
        }
        return new ShotPlanner.ShotScore(
                aggregation.weightedValue - currentPlanValue(currentOurEnergy),
                aggregation.weightedAbsoluteScore);
    }

    private void applyImmediateEvent(List<PlanOutcomeBranch> nextBranches,
                                     PlanOutcomeBranch branch,
                                     ImmediateEvent event) {
        if (branch == null || event == null || !(branch.probability > 0.0)) {
            return;
        }
        switch (event.kind) {
            case OUR_SHOT:
            case ENEMY_SHOT:
                branchShotEvent(nextBranches, branch, event);
                break;
            case WALL_HIT:
                applyWallHitEvent(branch, event.value);
                nextBranches.add(branch);
                break;
            case RAM:
                applyRamEvent(branch, event.ramEvent);
                nextBranches.add(branch);
                break;
            default:
                throw new IllegalStateException("Unhandled immediate event kind: " + event.kind);
        }
    }

    private void applyWallHitEvent(PlanOutcomeBranch branch,
                                   double wallDamage) {
        if (branch == null || !(wallDamage > 0.0) || !branch.ourAlive) {
            return;
        }
        branch.ourEnergy = Math.max(0.0, branch.ourEnergy - wallDamage);
        updateAliveStates(branch);
    }

    private void applyRamEvent(PlanOutcomeBranch branch,
                               RamEvent ramEvent) {
        if (branch == null || ramEvent == null || !branch.ourAlive || !branch.enemyAlive) {
            return;
        }
        branch.ourScore += ramEvent.ourScoreDelta;
        branch.opponentScore += ramEvent.opponentScoreDelta;
        branch.ourEnergy = Math.max(0.0, branch.ourEnergy - Math.max(0.0, ramEvent.ourEnergyLoss));
        branch.enemyEnergy = Math.max(0.0, branch.enemyEnergy - Math.max(0.0, ramEvent.enemyEnergyLoss));
        branch.ourCreditedDamageOnEnemy += Math.max(0.0, ramEvent.ourCreditedDamageOnEnemyDelta);
        branch.enemyCreditedDamageOnUs += Math.max(0.0, ramEvent.enemyCreditedDamageOnUsDelta);
        branch.ourKillBonusApplied = branch.ourKillBonusApplied || ramEvent.ourKillBonusApplied;
        branch.enemyKillBonusApplied = branch.enemyKillBonusApplied || ramEvent.enemyKillBonusApplied;
        updateAliveStates(branch);
    }

    private void branchShotEvent(List<PlanOutcomeBranch> nextBranches,
                                 PlanOutcomeBranch branch,
                                 ImmediateEvent event) {
        if (nextBranches == null || branch == null || event == null || !canResolveShotEvent(branch, event)) {
            if (nextBranches != null && branch != null && event != null) {
                nextBranches.add(branch);
            }
            return;
        }
        double clampedHitProbability = Math.max(0.0, Math.min(1.0, event.probability));
        double missProbability = 1.0 - clampedHitProbability;
        if (missProbability > 0.0) {
            PlanOutcomeBranch missBranch = branch.copyWithProbability(branch.probability * missProbability);
            applyShotOutcome(missBranch, event, false);
            nextBranches.add(missBranch);
        }
        if (!(clampedHitProbability > 0.0)) {
            return;
        }
        PlanOutcomeBranch hitBranch = branch.copyWithProbability(branch.probability * clampedHitProbability);
        applyShotOutcome(hitBranch, event, true);
        nextBranches.add(hitBranch);
    }

    private boolean canResolveShotEvent(PlanOutcomeBranch branch,
                                        ImmediateEvent event) {
        if (branch == null || event == null || !(event.shotPower >= ShotPlanner.MIN_FIRE_POWER)) {
            return false;
        }
        if (event.kind == ImmediateEventKind.OUR_SHOT) {
            if (event.requireShooterAlive && !branch.ourAlive) {
                return false;
            }
            if (event.deductEnergyCost && branch.ourEnergy + 1e-9 < event.shotPower) {
                return false;
            }
            return branch.enemyEnergy > 0.0 || event.deductEnergyCost;
        }
        if (!(branch.ourEnergy > 0.0)) {
            return false;
        }
        if (event.requireShooterAlive && !branch.enemyAlive) {
            return false;
        }
        if (event.deductEnergyCost && branch.enemyEnergy + 1e-9 < event.shotPower) {
            return false;
        }
        return true;
    }

    private void applyShotOutcome(PlanOutcomeBranch branch,
                                  ImmediateEvent event,
                                  boolean hit) {
        if (branch == null || event == null) {
            return;
        }
        boolean ourSide = event.kind == ImmediateEventKind.OUR_SHOT;
        boolean shooterAliveBeforeCost = ourSide ? branch.ourAlive : branch.enemyAlive;
        if (event.deductEnergyCost) {
            if (ourSide) {
                branch.ourEnergy = Math.max(0.0, branch.ourEnergy - event.shotPower);
            } else {
                branch.enemyEnergy = Math.max(0.0, branch.enemyEnergy - event.shotPower);
            }
            updateAliveStates(branch);
        }
        boolean shooterAliveAfterCost = ourSide ? branch.ourAlive : branch.enemyAlive;
        if (!hit) {
            return;
        }

        double targetEnergyBeforeHit = ourSide ? branch.enemyEnergy : branch.ourEnergy;
        double creditedDamage = RobocodeScoreUtil.creditedBulletDamage(event.shotPower, targetEnergyBeforeHit);
        if (!(creditedDamage > 0.0)) {
            return;
        }

        if (ourSide) {
            branch.ourScore += creditedDamage;
            branch.ourCreditedDamageOnEnemy += creditedDamage;
            if (!branch.ourKillBonusApplied
                    && RobocodeScoreUtil.isLethalDamage(targetEnergyBeforeHit, creditedDamage)) {
                branch.ourScore += RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * branch.ourCreditedDamageOnEnemy;
                branch.ourKillBonusApplied = true;
            }
            branch.enemyEnergy = Math.max(0.0, branch.enemyEnergy - creditedDamage);
            if (shooterAliveBeforeCost && shooterAliveAfterCost) {
                branch.ourEnergy += 3.0 * event.shotPower;
            }
        } else {
            branch.opponentScore += creditedDamage;
            branch.enemyCreditedDamageOnUs += creditedDamage;
            if (!branch.enemyKillBonusApplied
                    && RobocodeScoreUtil.isLethalDamage(targetEnergyBeforeHit, creditedDamage)) {
                branch.opponentScore += RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * branch.enemyCreditedDamageOnUs;
                branch.enemyKillBonusApplied = true;
            }
            branch.ourEnergy = Math.max(0.0, branch.ourEnergy - creditedDamage);
            if (shooterAliveBeforeCost && shooterAliveAfterCost) {
                branch.enemyEnergy += 3.0 * event.shotPower;
            }
        }
        updateAliveStates(branch);
    }

    private void updateAliveStates(PlanOutcomeBranch branch) {
        if (branch == null) {
            return;
        }
        boolean previousOurAlive = branch.ourAlive;
        boolean previousEnemyAlive = branch.enemyAlive;
        boolean currentOurAlive = branch.ourEnergy > 1e-9;
        boolean currentEnemyAlive = branch.enemyEnergy > 1e-9;
        if (previousOurAlive && !currentOurAlive && currentEnemyAlive) {
            branch.opponentScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        }
        if (previousEnemyAlive && !currentEnemyAlive && currentOurAlive) {
            branch.ourScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        }
        branch.ourAlive = currentOurAlive;
        branch.enemyAlive = currentEnemyAlive;
        branch.roundResolved = !currentOurAlive || !currentEnemyAlive;
    }

    private static boolean sameEventTime(double a,
                                         double b) {
        return Math.abs(a - b) <= EVENT_TIME_EPSILON;
    }

    private void settleResolvedBranches(List<PlanOutcomeBranch> source,
                                        List<PlanOutcomeBranch> unresolvedOut,
                                        BranchAggregation aggregation) {
        if (source == null || unresolvedOut == null || aggregation == null) {
            return;
        }
        for (PlanOutcomeBranch branch : source) {
            if (branch == null || !(branch.probability > 0.0)) {
                continue;
            }
            if (isSettledResolvedBranch(branch)) {
                BranchValue branchValue = resolvedBranchValue(branch);
                aggregation.weightedValue += branch.probability * branchValue.value;
                aggregation.weightedAbsoluteScore += branch.probability * branchValue.absoluteScore;
                aggregation.settledProbability += branch.probability;
                continue;
            }
            unresolvedOut.add(branch);
        }
    }

    private BranchValue evaluateBranchValue(ShotPlanner.ShotSelection selection,
                                            BranchedEventSchedule schedule,
                                            PlanOutcomeBranch branch) {
        if (branch.roundResolved) {
            return resolvedBranchValue(branch);
        }
        ContinuationDeltas continuationDeltas = estimateBoundedContinuationDeltas(
                schedule,
                branch);
        double projectedOurScore = branch.ourScore + continuationDeltas.expectedScoreGained;
        double projectedOpponentScore = branch.opponentScore + continuationDeltas.expectedScoreConceded;
        double projectedOurEnergy = Math.max(0.0, branch.ourEnergy + continuationDeltas.energyDelta);
        double projectedEnemyEnergy = Math.max(
                0.0,
                branch.enemyEnergy
                        - continuationDeltas.expectedEnemyEnergyLoss
                        + continuationDeltas.expectedEnemyEnergyGain
                        - continuationDeltas.expectedEnemyEnergyDrain);
        double projectedOurCreditedDamage = branch.ourCreditedDamageOnEnemy
                + Math.max(0.0, continuationDeltas.expectedEnemyEnergyLoss);
        double projectedEnemyCreditedDamage = branch.enemyCreditedDamageOnUs
                + Math.max(0.0, continuationDeltas.expectedOpponentCreditedDamage);
        double continuationFirePower = selection != null ? selection.continuationFirePower : 0.0;
        return new BranchValue(
                evaluateContinuationBranchValue(
                        projectedOurScore,
                        projectedOpponentScore,
                        projectedOurEnergy,
                        projectedEnemyEnergy,
                        0,
                        0,
                        projectedOurCreditedDamage,
                        projectedEnemyCreditedDamage,
                        continuationFirePower),
                projectedOurScore - projectedOpponentScore);
    }

    private static boolean isSettledResolvedBranch(PlanOutcomeBranch branch) {
        return branch != null && branch.roundResolved;
    }

    private static BranchValue resolvedBranchValue(PlanOutcomeBranch branch) {
        return new BranchValue(
                exactShareValue(branch.ourScore, branch.opponentScore),
                branch.ourScore - branch.opponentScore);
    }

    private double currentPlanValue(double currentOurEnergy) {
        ScoreContext context = scoreContext;
        if (context != null) {
            double denominator = context.ourScore
                    + context.opponentScore
                    + context.currentOurTerminalAdd
                    + context.currentOpponentTerminalAdd;
            if (denominator > 0.0) {
                return (context.ourScore + context.currentOurTerminalAdd) / denominator;
            }
            return context.ourScore - context.opponentScore;
        }
        return evaluateContinuationBranchValue(
                info.getOurScore(),
                info.getOpponentScore(),
                currentOurEnergy,
                currentEnemyEnergyForScoring(),
                0,
                0,
                info.getOurCreditedDamageOnEnemyThisRound(),
                info.getEnemyCreditedDamageOnUsThisRound(),
                0.0);
    }

    private static double exactShareValue(double ourScore,
                                          double opponentScore) {
        double denominator = ourScore + opponentScore;
        if (denominator > 0.0) {
            return ourScore / denominator;
        }
        return ourScore - opponentScore;
    }

    private void prepareScoreContext(RobotSnapshot robotState) {
        double ourScore = ScoreMaxScoreHistoryProfile.INSTANCE.getCombinedOurScore();
        double opponentScore = ScoreMaxScoreHistoryProfile.INSTANCE.getCombinedOpponentScore();
        boolean bonusEnabled = isEndOfRoundBonusEvEnabledForState();
        if (!bonusEnabled) {
            scoreContext = new ScoreContext(
                    ourScore, opponentScore, false, 0.0, 0.0, 0.0, 0.0);
            return;
        }

        EnemyInfo enemy = info.getEnemy();
        double currentEnemyEnergy = Math.max(0.0, enemy.energy);
        double currentWinProbability = WinProbabilityModel.estimateWinProbability(
                robotState.energy,
                currentEnemyEnergy,
                getAssumedWinProbabilityParams());
        EnemyInfo.PredictedPosition enemyAtCurrentTime = predictEnemyPositionAt(robotState.time);
        double baselineContinuationFirePower = shotPlanner.estimateContinuationFirePower(
                robotState.x,
                robotState.y,
                enemyAtCurrentTime,
                robotState.energy,
                robotState.gunCoolingRate,
                robotState.energy,
                currentEnemyEnergy);
        TerminalScoreEstimate currentTerminalEstimate = estimateTerminalScoreAdds(
                info.getOurCreditedDamageOnEnemyThisRound(),
                info.getEnemyCreditedDamageOnUsThisRound(),
                robotState.energy,
                currentEnemyEnergy,
                currentWinProbability,
                baselineContinuationFirePower);
        scoreContext = new ScoreContext(
                ourScore,
                opponentScore,
                true,
                currentEnemyEnergy,
                currentWinProbability,
                currentTerminalEstimate.ourTerminalAdd,
                currentTerminalEstimate.opponentTerminalAdd);
    }

    private double evaluateContinuationBranchValue(double ourScore,
                                                   double opponentScore,
                                                   double ourEnergy,
                                                   double enemyEnergy,
                                                   int ourReadyTicks,
                                                   int enemyReadyTicks,
                                                   double ourCreditedDamageOnEnemy,
                                                   double enemyCreditedDamageOnUs,
                                                   double continuationFirePower) {
        boolean bonusEnabled = isEndOfRoundBonusEvEnabledForState();
        int clampedReadyTickLead = Math.max(
                -MAX_READY_TICK_LEAD,
                Math.min(MAX_READY_TICK_LEAD, enemyReadyTicks - ourReadyTicks));
        double readinessEnergyEquivalent =
                clampedReadyTickLead * DEFAULT_READY_TICK_ENERGY_EQUIVALENT;
        double effectiveOurEnergy = Math.max(0.0, ourEnergy + 0.5 * readinessEnergyEquivalent);
        double effectiveEnemyEnergy = Math.max(0.0, enemyEnergy - 0.5 * readinessEnergyEquivalent);
        if (!bonusEnabled) {
            double denominator = ourScore + opponentScore;
            return denominator > 0.0
                    ? ourScore / denominator
                    : ourScore - opponentScore + 0.1 * (effectiveOurEnergy - effectiveEnemyEnergy);
        }
        double winProbability = WinProbabilityModel.estimateWinProbability(
                effectiveOurEnergy,
                effectiveEnemyEnergy,
                getAssumedWinProbabilityParams());
        TerminalScoreEstimate terminalEstimate = estimateTerminalScoreAdds(
                ourCreditedDamageOnEnemy,
                enemyCreditedDamageOnUs,
                ourEnergy,
                enemyEnergy,
                winProbability,
                continuationFirePower);
        double denominator = ourScore + opponentScore
                + terminalEstimate.ourTerminalAdd
                + terminalEstimate.opponentTerminalAdd;
        if (denominator <= 0.0) {
            return winProbability;
        }
        return (ourScore + terminalEstimate.ourTerminalAdd) / denominator;
    }

    private boolean isEndOfRoundBonusEvEnabledForState() {
        if (!ENABLE_END_OF_ROUND_BONUS_EV) {
            return false;
        }
        EnemyInfo enemy = info.getEnemy();
        return enemy != null && enemy.alive && enemy.seenThisRound;
    }

    private TerminalScoreEstimate estimateTerminalScoreAdds(double ourCreditedDamageOnEnemy,
                                                            double enemyCreditedDamageOnUs,
                                                            double ourEnergy,
                                                            double enemyEnergy,
                                                            double ourWinProbability,
                                                            double continuationFirePower) {
        if (!isEndOfRoundBonusEvEnabledForState()) {
            return new TerminalScoreEstimate(0.0, 0.0);
        }

        double clampedOurWinProbability = Math.max(0.0, Math.min(1.0, ourWinProbability));
        double clampedEnemyWinProbability = 1.0 - clampedOurWinProbability;
        double ourPower = sanitizeContinuationPower(continuationFirePower, ourEnergy);
        double enemyPower = sanitizeContinuationPower(assumedEnemyContinuationPower(enemyEnergy), enemyEnergy);

        double ourDistance = currentEnemyDistance();
        int ourFlightTicks = nominalFlightTicksForPower(ourDistance, ourPower);
        int enemyFlightTicks = nominalFlightTicksForPower(ourDistance, enemyPower);
        double ourHitRate = ourPower >= MIN_CONTINUATION_POWER
                ? info.getBulletPowerHitRateTracker().estimateHitRate(ourFlightTicks) * ShotPlanner.targetingDataHitRateScale()
                : 0.0;
        double enemyHitRate = enemyPower >= MIN_CONTINUATION_POWER
                ? info.getEnemyBulletHitRateTracker().estimateHitRate(enemyFlightTicks)
                : 0.0;

        double ourDamageRate = continuationBulletDamageRate(ourPower, ourHitRate);
        double enemyDamageRate = continuationBulletDamageRate(enemyPower, enemyHitRate);
        double ourNetSelfEnergyLossRate = continuationNetSelfEnergyLossRate(ourPower, ourHitRate);
        double enemyNetSelfEnergyLossRate = continuationNetSelfEnergyLossRate(enemyPower, enemyHitRate);
        double enemyLossRate = ourDamageRate + enemyNetSelfEnergyLossRate;
        double ourLossRate = enemyDamageRate + ourNetSelfEnergyLossRate;

        // Advance both robots under the same continuation model and stop at the
        // first zero-energy event; after that point the coupled tail is invalid.
        double fightDuration = terminalFightDuration(ourEnergy, ourLossRate, enemyEnergy, enemyLossRate);
        double extraOurCreditedDamage = expectedCreditedDamageOverDuration(ourDamageRate, fightDuration);
        double extraEnemyCreditedDamage = expectedCreditedDamageOverDuration(enemyDamageRate, fightDuration);

        double ourTerminalIfWin = RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1
                + RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER
                * (Math.max(0.0, ourCreditedDamageOnEnemy) + extraOurCreditedDamage);
        double enemyTerminalIfWin = RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1
                + RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER
                * (Math.max(0.0, enemyCreditedDamageOnUs) + extraEnemyCreditedDamage);
        return new TerminalScoreEstimate(
                clampedOurWinProbability * ourTerminalIfWin,
                clampedEnemyWinProbability * enemyTerminalIfWin);
    }

    private static double continuationBulletDamageRate(double power, double hitRate) {
        if (!(power >= MIN_CONTINUATION_POWER)) {
            return 0.0;
        }
        return hitRate * robocode.Rules.getBulletDamage(power) / continuationCadenceTicks(power);
    }

    private static double continuationNetSelfEnergyLossRate(double power, double hitRate) {
        if (!(power >= MIN_CONTINUATION_POWER)) {
            return 0.0;
        }
        return (power - 3.0 * hitRate * power) / continuationCadenceTicks(power);
    }

    private static double continuationCadenceTicks(double power) {
        return Math.max(1.0, (1.0 + power / 5.0) / 0.1);
    }

    private static double terminalFightDuration(double ourEnergy,
                                                double ourLossRate,
                                                double enemyEnergy,
                                                double enemyLossRate) {
        double ourTimeToZero = timeToZeroEnergy(ourEnergy, ourLossRate);
        double enemyTimeToZero = timeToZeroEnergy(enemyEnergy, enemyLossRate);
        return Math.min(ourTimeToZero, enemyTimeToZero);
    }

    private static double timeToZeroEnergy(double energy, double lossRate) {
        if (!(energy > 0.0)) {
            return 0.0;
        }
        if (!(lossRate > MIN_TERMINAL_RATE)) {
            return Double.POSITIVE_INFINITY;
        }
        return energy / lossRate;
    }

    private static double expectedCreditedDamageOverDuration(double damageRate,
                                                             double duration) {
        if (!(damageRate > 0.0) || !(duration > 0.0) || !Double.isFinite(duration)) {
            return 0.0;
        }
        return damageRate * duration;
    }

    private double currentEnemyDistance() {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.seenThisRound) {
            return 400.0;
        }
        return Math.max(1.0, enemy.distance);
    }

    private double currentEnemyEnergyForTerminalScoring(ScoreContext context) {
        if (context != null) {
            return context.currentEnemyEnergy;
        }
        EnemyInfo enemy = info.getEnemy();
        return enemy != null ? Math.max(0.0, enemy.energy) : 0.0;
    }

    private static int nominalFlightTicksForPower(double distance, double power) {
        if (!(power >= MIN_CONTINUATION_POWER)) {
            return 65;
        }
        return Wave.nominalFlightTicks(distance, Wave.bulletSpeed(power));
    }

    private static double sanitizeContinuationPower(double power, double availableEnergy) {
        if (!(power >= MIN_CONTINUATION_POWER) || !(availableEnergy >= MIN_CONTINUATION_POWER)) {
            return 0.0;
        }
        return Math.min(power, Math.min(ShotPlanner.MAX_FIRE_POWER, availableEnergy));
    }

    private double assumedEnemyContinuationPower(double enemyEnergy) {
        EnemyInfo enemy = info.getEnemy();
        double lastDetectedBulletPower = enemy != null ? enemy.lastDetectedBulletPower : Double.NaN;
        double assumedPower = Double.isFinite(lastDetectedBulletPower)
                ? lastDetectedBulletPower
                : DEFAULT_ENEMY_CONTINUATION_POWER;
        return Math.min(assumedPower, Math.max(0.0, enemyEnergy));
    }

    public CandidatePath getLastSelectedPath() {
        return lastSelectedPath;
    }

    public List<PathWaveIntersection> getLastSelectedPathIntersections() {
        return lastSelectedPathIntersections;
    }

    public List<CandidatePath> getLastSelectedSafeSpotPaths() {
        return lastSelectedSafeSpotPaths;
    }

    private WinProbabilityModel.Params getAssumedWinProbabilityParams() {
        double survivalPrior = adjustedSurvivalPrior(info.getRoundOutcomeProfile().getSurvivalPrior());
        double clamped = Math.max(MIN_PRIOR_WIN_PROBABILITY,
                Math.min(MAX_PRIOR_WIN_PROBABILITY, survivalPrior));
        return new WinProbabilityModel.Params(
                clamped,
                DEFAULT_PRIOR_WEIGHT_FLOOR,
                DEFAULT_PRIOR_WEIGHT_EXPONENT,
                DEFAULT_ENERGY_LEAD_SCALE,
                DEFAULT_ENERGY_LEAD_OFFSET,
                DEFAULT_LOW_ENERGY_UNCERTAINTY_SCALE);
    }

    private double adjustedSurvivalPrior(double survivalPrior) {
        if (info == null || info.getRobot() == null || info.getDataStore() == null) {
            return survivalPrior;
        }
        if (!info.getDataStore().isFirstBattleForTrackedOpponent()) {
            return survivalPrior;
        }
        if (scoreMaxSelectedRoundCount <= 0) {
            return survivalPrior;
        }
        double optimisticTarget = Math.max(survivalPrior, FIRST_BATTLE_SCOREMAX_PRIOR_TARGET);
        double blend = firstBattleScoreMaxPriorBlend(scoreMaxSelectedRoundCount - 1);
        return survivalPrior + blend * (optimisticTarget - survivalPrior);
    }

    private static double firstBattleScoreMaxPriorBlend(int scoreMaxRoundIndex) {
        if (scoreMaxRoundIndex <= 0) {
            return 1.0;
        }
        if (scoreMaxRoundIndex >= FIRST_BATTLE_SCOREMAX_PRIOR_ROUNDS - 1) {
            return 0.0;
        }
        double t = scoreMaxRoundIndex / (double) (FIRST_BATTLE_SCOREMAX_PRIOR_ROUNDS - 1);
        double smooth = t * t * (3.0 - 2.0 * t);
        return 1.0 - smooth;
    }

    private void noteScoreMaxRoundSelection() {
        if (info == null || info.getRobot() == null) {
            return;
        }
        int roundNum = info.getRobot().getRoundNum();
        if (roundNum < 0 || roundNum == lastCountedScoreMaxRound) {
            return;
        }
        lastCountedScoreMaxRound = roundNum;
        scoreMaxSelectedRoundCount++;
    }

    private EnemyInfo.PredictedPosition predictEnemyPositionAt(long time) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        return enemy.predictPositionAtTime(
                time, info.getBattlefieldWidth(), info.getBattlefieldHeight());
    }

    private double currentEnemyEnergyForScoring() {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, enemy.energy);
    }

    private PlanningBranch createOffensivePowerBranch(double offensivePower) {
        return new PlanningBranch(null, offensivePower, false, true, true);
    }

    private static PlanningBranch createNoFireBranch() {
        return new PlanningBranch(null, 0.0, false, false, true);
    }

    private PlanningBranch createShadowOnlyBranch(double offensivePower) {
        return new PlanningBranch(null, offensivePower, true, false, true);
    }

    private PlanningBranch createOffensiveOnlyBranch(double offensivePower) {
        return new PlanningBranch(null, offensivePower, false, true, true);
    }

    private PlanningBranch createFirstPassBranch(FirstPassShotMode shotMode, double offensivePower) {
        if (shotMode == FirstPassShotMode.NO_FIRE) {
            return createNoFireBranch();
        }
        if (shotMode == FirstPassShotMode.SHADOW) {
            return createShadowOnlyBranch(offensivePower);
        }
        return createOffensivePowerBranch(offensivePower);
    }

    private FirstPassShotMode selectFirstPassShotMode() {
        if (lastSelectedFamilyPaths == null || lastSelectedFamilyPaths.isEmpty()) {
            return FirstPassShotMode.OFFENSIVE;
        }
        if (lastSelectedFirePower < ShotPlanner.MIN_FIRE_POWER - POWER_SAMPLE_EPSILON) {
            return FirstPassShotMode.NO_FIRE;
        }
        if (lastSelectedShotWasShadow
                && Math.abs(lastSelectedFirePower - ShotPlanner.SHADOW_FIRE_POWER) <= POWER_SAMPLE_EPSILON) {
            return FirstPassShotMode.SHADOW;
        }
        return FirstPassShotMode.OFFENSIVE;
    }

    private static boolean firstPassIncludesOffensiveShot(FirstPassShotMode shotMode) {
        return shotMode == FirstPassShotMode.OFFENSIVE;
    }

    private CandidatePath firstPassContinuityCandidate(List<CandidatePath> paths) {
        if (paths == null || paths.isEmpty()
                || lastSelectedFamilyPaths == null || lastSelectedFamilyPaths.isEmpty()) {
            return null;
        }
        CandidatePath firstCandidate = paths.get(0);
        CandidatePath previousPrimaryFamily = lastSelectedFamilyPaths.get(0);
        if (firstCandidate == null || previousPrimaryFamily == null) {
            return null;
        }
        return firstCandidate.familyId == previousPrimaryFamily.familyId
                ? firstCandidate
                : null;
    }

    private double seededOffensivePower(double maxPower) {
        if (!(maxPower >= ShotPlanner.MIN_FIRE_POWER)) {
            return 0.0;
        }
        if (retainedOffensivePower >= ShotPlanner.MIN_FIRE_POWER) {
            return Math.min(maxPower, retainedOffensivePower);
        }
        return Math.min(maxPower, DEFAULT_TRACKING_FIRE_POWER);
    }

    private static void appendUniquePower(List<Double> powers, double power) {
        for (double existing : powers) {
            if (Math.abs(existing - power) <= POWER_SAMPLE_EPSILON) {
                return;
            }
        }
        powers.add(power);
    }

    private static double maxCandidateOffensivePower(double availableEnergy,
                                                     double enemyEnergyForScoring) {
        if (!(availableEnergy >= ShotPlanner.MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.min(
                ShotPlanner.MAX_FIRE_POWER,
                Math.min(availableEnergy, RobocodeScoreUtil.powerToDealDamage(enemyEnergyForScoring)));
    }

    private List<Double> buildCandidateOffensivePowers(double maxPower) {
        List<Double> powers = new ArrayList<>();
        if (!(maxPower >= ShotPlanner.MIN_FIRE_POWER)) {
            return powers;
        }
        if (Math.abs(maxPower - ShotPlanner.MIN_FIRE_POWER) <= POWER_SAMPLE_EPSILON) {
            powers.add(ShotPlanner.MIN_FIRE_POWER);
            return powers;
        }
        for (int i = 0; i < GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT; i++) {
            double fraction = i / (double) (GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT - 1);
            double power = ShotPlanner.MIN_FIRE_POWER
                    + (maxPower - ShotPlanner.MIN_FIRE_POWER) * fraction;
            appendUniquePower(powers, power);
        }
        return powers;
    }

    private static double clampCandidatePower(double power, double maxPower) {
        if (!(maxPower >= ShotPlanner.MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.max(ShotPlanner.MIN_FIRE_POWER, Math.min(maxPower, power));
    }

    private void ensureOffensivePowerBracket(List<Double> globalPowers, double maxPower) {
        if (offensivePowerBracketValid) {
            return;
        }
        recenterOffensivePowerBracket(retainedOffensivePower, globalPowers, maxPower);
    }

    private void recenterOffensivePowerBracket(double centerPower,
                                               List<Double> globalPowers,
                                               double maxPower) {
        double clampedCenterPower = clampCandidatePower(centerPower, maxPower);
        if (!(clampedCenterPower >= ShotPlanner.MIN_FIRE_POWER)) {
            offensivePowerBracketValid = false;
            offensivePowerBracketMin = Double.NaN;
            offensivePowerBracketMax = Double.NaN;
            return;
        }
        double lowerAnchor = ShotPlanner.MIN_FIRE_POWER;
        double upperAnchor = maxPower;
        for (double globalPower : globalPowers) {
            if (globalPower < clampedCenterPower - POWER_SAMPLE_EPSILON) {
                lowerAnchor = globalPower;
                continue;
            }
            if (globalPower > clampedCenterPower + POWER_SAMPLE_EPSILON) {
                upperAnchor = globalPower;
                break;
            }
        }

        double bracketMin = lowerAnchor < clampedCenterPower - POWER_SAMPLE_EPSILON
                ? 0.5 * (lowerAnchor + clampedCenterPower)
                : clampedCenterPower;
        double bracketMax = upperAnchor > clampedCenterPower + POWER_SAMPLE_EPSILON
                ? 0.5 * (clampedCenterPower + upperAnchor)
                : clampedCenterPower;
        offensivePowerBracketMin = bracketMin;
        offensivePowerBracketMax = bracketMax;
        offensivePowerBracketValid = bracketMax - bracketMin > POWER_SAMPLE_EPSILON;
    }

    private void appendBracketEndpointPowers(List<Double> powers, double maxPower) {
        if (!offensivePowerBracketValid) {
            return;
        }
        appendUniquePower(powers, clampCandidatePower(offensivePowerBracketMin, maxPower));
        appendUniquePower(powers, clampCandidatePower(offensivePowerBracketMax, maxPower));
    }

    private boolean offensivePowerOutsideBracket(double power) {
        if (!offensivePowerBracketValid) {
            return false;
        }
        return power < offensivePowerBracketMin - POWER_SAMPLE_EPSILON
                || power > offensivePowerBracketMax + POWER_SAMPLE_EPSILON;
    }

    private static PlanEvaluation findEvaluationAtPower(List<PlanEvaluation> evaluations, double power) {
        if (evaluations == null) {
            return null;
        }
        PlanEvaluation bestMatch = null;
        for (PlanEvaluation evaluation : evaluations) {
            if (evaluation == null || evaluation.plan == null) {
                continue;
            }
            if (Math.abs(evaluation.plan.firePower - power) > POWER_SAMPLE_EPSILON) {
                continue;
            }
            if (bestMatch == null || isBetterScore(
                    evaluation.score,
                    evaluation.absoluteScore,
                    bestMatch.score,
                    bestMatch.absoluteScore)) {
                bestMatch = evaluation;
            }
        }
        return bestMatch;
    }

    private static double parabolicMaximum(double leftPower,
                                           double leftScore,
                                           double centerPower,
                                           double centerScore,
                                           double rightPower,
                                           double rightScore) {
        double leftSlope = (centerScore - leftScore) / (centerPower - leftPower);
        double rightSlope = (rightScore - leftScore) / (rightPower - leftPower);
        double quadratic = (rightSlope - leftSlope) / (rightPower - centerPower);
        if (!(quadratic < -POWER_SAMPLE_EPSILON)) {
            return Double.NaN;
        }
        double linear = leftSlope - quadratic * (leftPower + centerPower);
        return -linear / (2.0 * quadratic);
    }

    private static double midpoint(double a, double b) {
        return 0.5 * (a + b);
    }

    private static double fallbackGoldenSample(PlanEvaluation leftEvaluation,
                                               PlanEvaluation rightEvaluation,
                                               double centerPower) {
        if (leftEvaluation == null || rightEvaluation == null) {
            return Double.NaN;
        }
        if (isBetterScore(
                rightEvaluation.score,
                rightEvaluation.absoluteScore,
                leftEvaluation.score,
                leftEvaluation.absoluteScore)) {
            return centerPower + GOLDEN_SECTION_LOCAL_STEP * (rightEvaluation.plan.firePower - centerPower);
        }
        if (isBetterScore(
                leftEvaluation.score,
                leftEvaluation.absoluteScore,
                rightEvaluation.score,
                rightEvaluation.absoluteScore)) {
            return centerPower - GOLDEN_SECTION_LOCAL_STEP * (centerPower - leftEvaluation.plan.firePower);
        }
        return Double.NaN;
    }

    private static double proposeOffensivePowerProbe(PlanEvaluation leftEvaluation,
                                                     PlanEvaluation centerEvaluation,
                                                     PlanEvaluation rightEvaluation) {
        if (leftEvaluation == null || centerEvaluation == null || rightEvaluation == null) {
            return Double.NaN;
        }
        double leftPower = leftEvaluation.plan.firePower;
        double centerPower = centerEvaluation.plan.firePower;
        double rightPower = rightEvaluation.plan.firePower;
        double minimumSpacing = Math.max(1e-3, 0.05 * (rightPower - leftPower));
        double proposal = parabolicMaximum(
                leftPower,
                leftEvaluation.score,
                centerPower,
                centerEvaluation.score,
                rightPower,
                rightEvaluation.score);
        if (proposal > leftPower + minimumSpacing
                && proposal < rightPower - minimumSpacing
                && Math.abs(proposal - centerPower) > minimumSpacing
                && Math.abs(proposal - leftPower) > minimumSpacing
                && Math.abs(proposal - rightPower) > minimumSpacing) {
            return proposal;
        }
        proposal = fallbackGoldenSample(leftEvaluation, rightEvaluation, centerPower);
        if (proposal > leftPower + minimumSpacing
                && proposal < rightPower - minimumSpacing
                && Math.abs(proposal - centerPower) > minimumSpacing) {
            return proposal;
        }
        return Double.NaN;
    }

    private void advanceOffensivePowerBracket(List<PlanEvaluation> offensiveEvaluations,
                                              double centerPower,
                                              List<Double> globalPowers,
                                              double maxPower) {
        if (!offensivePowerBracketValid) {
            recenterOffensivePowerBracket(centerPower, globalPowers, maxPower);
            return;
        }
        double leftPower = clampCandidatePower(offensivePowerBracketMin, maxPower);
        double rightPower = clampCandidatePower(offensivePowerBracketMax, maxPower);
        if (!(leftPower < centerPower - POWER_SAMPLE_EPSILON
                && rightPower > centerPower + POWER_SAMPLE_EPSILON)) {
            recenterOffensivePowerBracket(centerPower, globalPowers, maxPower);
            return;
        }

        PlanEvaluation leftEvaluation = findEvaluationAtPower(offensiveEvaluations, leftPower);
        PlanEvaluation centerEvaluation = findEvaluationAtPower(offensiveEvaluations, centerPower);
        PlanEvaluation rightEvaluation = findEvaluationAtPower(offensiveEvaluations, rightPower);
        if (leftEvaluation == null || centerEvaluation == null || rightEvaluation == null) {
            recenterOffensivePowerBracket(centerPower, globalPowers, maxPower);
            return;
        }

        double proposal = proposeOffensivePowerProbe(leftEvaluation, centerEvaluation, rightEvaluation);
        double nextLeftProbe;
        double nextRightProbe;
        if (proposal > centerPower + POWER_SAMPLE_EPSILON) {
            nextLeftProbe = midpoint(leftPower, centerPower);
            nextRightProbe = proposal;
        } else if (proposal < centerPower - POWER_SAMPLE_EPSILON) {
            nextLeftProbe = proposal;
            nextRightProbe = midpoint(centerPower, rightPower);
        } else {
            nextLeftProbe = midpoint(leftPower, centerPower);
            nextRightProbe = midpoint(centerPower, rightPower);
        }

        nextLeftProbe = clampCandidatePower(nextLeftProbe, maxPower);
        nextRightProbe = clampCandidatePower(nextRightProbe, maxPower);
        if (!(nextLeftProbe < centerPower - POWER_SAMPLE_EPSILON
                && nextRightProbe > centerPower + POWER_SAMPLE_EPSILON)) {
            recenterOffensivePowerBracket(centerPower, globalPowers, maxPower);
            return;
        }
        offensivePowerBracketMin = nextLeftProbe;
        offensivePowerBracketMax = nextRightProbe;
        offensivePowerBracketValid = true;
    }

    private boolean hasActiveRealEnemyWave(double x, double y, long currentTime) {
        for (Wave wave : info.getEnemyWaves()) {
            if (!wave.hasPassed(x, y, currentTime)) {
                return true;
            }
        }
        return false;
    }

    private BattlePlan buildDisabledRamPlan(double x,
                                            double y,
                                            double heading,
                                            double velocity,
                                            double gunHeading,
                                            long currentTime) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound || enemy.energy > 0.0) {
            return null;
        }
        if (hasActiveRealEnemyWave(x, y, currentTime)) {
            return null;
        }

        long nextTick = currentTime + 1;
        EnemyInfo.PredictedPosition predictedEnemy = enemy.predictPositionAtTime(
                nextTick, info.getBattlefieldWidth(), info.getBattlefieldHeight());
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                x, y, heading, velocity, predictedEnemy.x, predictedEnemy.y);
        // Against a disabled opponent, close distance for a ram instead of firing.
        return new BattlePlan(
                instruction[0],
                instruction[1],
                optimalGunTurnFromHeading(DEFAULT_TRACKING_FIRE_POWER, gunHeading),
                0.0);
    }

    private static boolean isBetterScore(double score,
                                         double absoluteScore,
                                         double bestScore,
                                         double bestAbsoluteScore) {
        return score > bestScore || (score == bestScore && absoluteScore > bestAbsoluteScore);
    }

    private static List<CandidatePath> selectTopDistinctFamilyPaths(List<PlanEvaluation> evaluations, int maxFamilies) {
        if (evaluations == null || evaluations.isEmpty() || maxFamilies <= 0) {
            return new ArrayList<>();
        }
        evaluations.sort((a, b) -> {
            int scoreCompare = Double.compare(b.score, a.score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return Double.compare(b.absoluteScore, a.absoluteScore);
        });
        List<CandidatePath> selectedPaths = new ArrayList<>(Math.min(maxFamilies, evaluations.size()));
        Set<Long> selectedFamilyIds = new HashSet<>();
        for (PlanEvaluation evaluation : evaluations) {
            if (evaluation == null || evaluation.path == null) {
                continue;
            }
            if (!selectedFamilyIds.add(evaluation.path.familyId)) {
                continue;
            }
            selectedPaths.add(evaluation.path);
            if (selectedPaths.size() >= maxFamilies) {
                break;
            }
        }
        return selectedPaths;
    }

    private PlanEvaluation evaluatePlanForPath(CandidatePath path,
                                               PlanningBranch branch,
                                               RobotSnapshot robotState,
                                               int firstFiringTickOffset,
                                               double currentGunHeading,
                                               double currentOurEnergy,
                                               PathIntersectionContext pathIntersectionContext,
                                               FireWindowContext fireWindowContext) {
        FiringResult firingResult = evaluateFiringAlongPath(
                path,
                firstFiringTickOffset,
                branch,
                currentGunHeading,
                robotState.gunCoolingRate,
                currentOurEnergy,
                pathIntersectionContext,
                fireWindowContext);
        if (!firingResult.valid) {
            return null;
        }

        // First-tick movement commands from path
        double[] instruction = path.segmentLegs.isEmpty()
                ? PhysicsUtil.computeMovementInstruction(
                        robotState.x, robotState.y, robotState.heading, robotState.velocity,
                        path.firstTargetX, path.firstTargetY)
                : PhysicsUtil.computeMovementInstruction(
                        robotState.x, robotState.y, robotState.heading, robotState.velocity,
                        path.segmentLegs.get(0).targetX,
                        path.segmentLegs.get(0).targetY,
                        PhysicsUtil.EndpointBehavior.PARK_AND_WAIT);

        BattlePlan plan = new BattlePlan(
                instruction[0], instruction[1],
                firingResult.firstTickGunTurn, firingResult.firstTickFirePower);

        return new PlanEvaluation(
                plan,
                path,
                firingResult.pathIntersections,
                firingResult.shotSelection.score,
                firingResult.shotSelection.absoluteScore,
                firingResult.shotSelection.shadowShot);
    }

    public BattlePlan getBestPlan() {
        noteScoreMaxRoundSelection();
        RobotSnapshot robotState = info.captureRobotSnapshot();
        invalidateSelectedPathStateIfWaveDangerChanged();
        BattlePlan disabledRamPlan = buildDisabledRamPlan(
                robotState.x,
                robotState.y,
                robotState.heading,
                robotState.velocity,
                robotState.gunHeading,
                robotState.time);
        if (disabledRamPlan != null) {
            lastSelectedPath = null;
            lastSelectedFamilyPaths = new ArrayList<>();
            lastSelectedSafeSpotPaths = new ArrayList<>();
            lastSelectedPathIntersections = new ArrayList<>();
            scoreContext = null;
            return disabledRamPlan;
        }

        double currentGunHeat = robotState.gunHeat;
        int firstFiringTickOffset = firstFiringTickOffset(currentGunHeat);
        double currentOurEnergy = robotState.energy;
        double currentGunHeading = robotState.gunHeading;
        prepareScoreContext(robotState);
        PathGenerationContext pathGenerationContext = movement.createPathGenerationContext();
        pathGenerationContext.minPathTicks = firstFiringTickOffset;
        double enemyEnergyForScoring = currentEnemyEnergyForScoring();
        double maxOffensivePower = maxCandidateOffensivePower(currentOurEnergy, enemyEnergyForScoring);
        double firstPassOffensivePower = seededOffensivePower(maxOffensivePower);
        FirstPassShotMode continuityShotMode = selectFirstPassShotMode();

        PlanSelection firstPassSelection = new PlanSelection();
        List<PlanEvaluation> firstPassEvaluations = new ArrayList<>();
        List<CandidatePath> paths = movement.generateCandidatePaths(pathGenerationContext, lastSelectedFamilyPaths);
        CandidatePath firstPassContinuityCandidate = firstPassContinuityCandidate(paths);
        for (CandidatePath path : paths) {
            PlanningBranch firstPassBranch = path == firstPassContinuityCandidate
                    ? createFirstPassBranch(continuityShotMode, firstPassOffensivePower)
                    : createOffensivePowerBranch(firstPassOffensivePower);
            PlanEvaluation evaluation = evaluatePlanForPath(
                    path,
                    firstPassBranch,
                    robotState,
                    firstFiringTickOffset,
                    currentGunHeading,
                    currentOurEnergy,
                    null,
                    null);
            if (evaluation != null) {
                firstPassEvaluations.add(evaluation);
            }
            firstPassSelection.consider(evaluation);
        }

        PlanSelection selection = firstPassSelection;
        if (firstPassSelection.bestPath != null) {
            FirstPassShotMode bestPathFirstPassShotMode = firstPassSelection.bestPath == firstPassContinuityCandidate
                    ? continuityShotMode
                    : FirstPassShotMode.OFFENSIVE;
            double priorOffensivePower = firstPassOffensivePower;
            List<Double> globalOffensivePowers = buildCandidateOffensivePowers(maxOffensivePower);
            List<Double> offensivePowers = new ArrayList<>(globalOffensivePowers);
            ensureOffensivePowerBracket(globalOffensivePowers, maxOffensivePower);
            appendBracketEndpointPowers(offensivePowers, maxOffensivePower);
            FireWindowContext bestPathFireWindow = buildFireWindowContext(
                    firstPassSelection.bestPath,
                    firstFiringTickOffset,
                    currentOurEnergy,
                    null);
            PlanSelection refinedSelection = new PlanSelection();
            PlanSelection offensiveSelection = new PlanSelection();
            List<PlanEvaluation> offensiveEvaluations = new ArrayList<>();
            refinedSelection.consider(firstPassSelection.bestEvaluation);
            if (firstPassIncludesOffensiveShot(bestPathFirstPassShotMode)) {
                offensiveSelection.consider(firstPassSelection.bestEvaluation);
            }
            if (firstPassIncludesOffensiveShot(bestPathFirstPassShotMode)
                    && firstPassSelection.bestEvaluation != null) {
                offensiveEvaluations.add(firstPassSelection.bestEvaluation);
            }
            if (bestPathFirstPassShotMode != FirstPassShotMode.NO_FIRE) {
                PlanEvaluation noFireEvaluation = evaluatePlanForPath(
                        firstPassSelection.bestPath,
                        createNoFireBranch(),
                        robotState,
                        firstFiringTickOffset,
                        currentGunHeading,
                        currentOurEnergy,
                        null,
                        bestPathFireWindow);
                refinedSelection.consider(noFireEvaluation);
            }
            if (bestPathFirstPassShotMode != FirstPassShotMode.SHADOW) {
                PlanEvaluation shadowEvaluation = evaluatePlanForPath(
                        firstPassSelection.bestPath,
                        createShadowOnlyBranch(firstPassOffensivePower),
                        robotState,
                        firstFiringTickOffset,
                        currentGunHeading,
                        currentOurEnergy,
                        null,
                        bestPathFireWindow);
                refinedSelection.consider(shadowEvaluation);
            }
            if (!offensivePowers.isEmpty()) {
                for (double offensivePower : offensivePowers) {
                    if (firstPassIncludesOffensiveShot(bestPathFirstPassShotMode)
                            && Math.abs(offensivePower - firstPassOffensivePower) <= POWER_SAMPLE_EPSILON) {
                        continue;
                    }
                    PlanEvaluation evaluation = evaluatePlanForPath(
                            firstPassSelection.bestPath,
                            createOffensiveOnlyBranch(offensivePower),
                            robotState,
                            firstFiringTickOffset,
                            currentGunHeading,
                            currentOurEnergy,
                            null,
                            bestPathFireWindow);
                    if (evaluation != null) {
                        offensiveEvaluations.add(evaluation);
                    }
                    offensiveSelection.consider(evaluation);
                    refinedSelection.consider(evaluation);
                }
            }
            if (offensiveSelection.bestPlan != null
                    && offensiveSelection.bestPlan.firePower >= ShotPlanner.MIN_FIRE_POWER) {
                double bestOffensivePower = offensiveSelection.bestPlan.firePower;
                retainedOffensivePower = bestOffensivePower;
                if (selectionFired(refinedSelection.bestPlan)
                        || offensivePowerOutsideBracket(bestOffensivePower)
                        || Math.abs(bestOffensivePower - priorOffensivePower) > POWER_SAMPLE_EPSILON
                        || !offensivePowerBracketValid) {
                    recenterOffensivePowerBracket(bestOffensivePower, globalOffensivePowers, maxOffensivePower);
                } else {
                    advanceOffensivePowerBracket(
                            offensiveEvaluations,
                            priorOffensivePower,
                            globalOffensivePowers,
                            maxOffensivePower);
                }
            }
            if (refinedSelection.bestPlan != null) {
                selection = refinedSelection;
            }
        }

        // Fallback: if no paths generated (shouldn't happen), return a do-nothing plan
        if (selection.bestPlan == null) {
            selection.bestPlan = new BattlePlan(
                    0.0,
                    0.0,
                    optimalGunTurnFromHeading(DEFAULT_TRACKING_FIRE_POWER, currentGunHeading),
                    0.0);
        }

        BattlePlan bestPlan = selection.bestPlan;
        lastSelectedPath = selection.bestPath;
        lastSelectedFamilyPaths = selectTopDistinctFamilyPaths(firstPassEvaluations, MAX_CARRIED_FORWARD_FAMILIES);
        lastSelectedSafeSpotPaths = new ArrayList<>();
        if (selection.bestPath != null) {
            lastSelectedSafeSpotPaths = RenderPathSelector.collectFirstSegmentDisplayPaths(paths, selection.bestPath);
        }
        lastSelectedPathIntersections = selection.bestPathIntersections;
        lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();
        lastSelectedFirePower = bestPlan.firePower;
        lastSelectedShotWasShadow = selection.bestShotWasShadow;
        scoreContext = null;
        return bestPlan;
    }

    private static boolean selectionFired(BattlePlan plan) {
        return plan != null && plan.firePower >= ShotPlanner.MIN_FIRE_POWER;
    }

    private void invalidateSelectedPathStateIfWaveDangerChanged() {
        long currentRevision = info.getEnemyWaveDangerRevision();
        if (currentRevision == lastSelectedWaveDangerRevision) {
            return;
        }
        lastSelectedPath = null;
        lastSelectedFamilyPaths = new ArrayList<>();
        lastSelectedSafeSpotPaths = new ArrayList<>();
        lastSelectedPathIntersections = new ArrayList<>();
        lastSelectedWaveDangerRevision = currentRevision;
    }

    String describeSkippedTurnDiagnostics() {
        return movement != null
                ? movement.describeLatestPathPlanningDiagnostics()
                : "planning=n/a";
    }
}
