package oog.mega.saguaro.mode.scoremax;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
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

public final class ScoreMaxPlanner {
    private static final double POWER_SAMPLE_EPSILON = 1e-6;
    private static final double GOLDEN_SECTION_LOCAL_STEP = 0.3819660112501051;

    public static final class Config {
        public static final Config DEFAULT = new Config(null, true, true, BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER);
        public static final Config ANTI_BASIC_SURFER = new Config(
                new double[]{
                        Math.nextAfter(2.45, Double.NEGATIVE_INFINITY),
                        1.95, 1.45, 0.95, 0.65, 0.45, 0.15
                },
                false,
                false,
                1.95);

        final double[] fixedCandidatePowers;
        final boolean allowShadow;
        final boolean capByEnemyEnergy;
        final double defaultTrackingPower;

        public Config(double[] fixedCandidatePowers,
                      boolean allowShadow,
                      boolean capByEnemyEnergy,
                      double defaultTrackingPower) {
            this.fixedCandidatePowers = fixedCandidatePowers;
            this.allowShadow = allowShadow;
            this.capByEnemyEnergy = capByEnemyEnergy;
            this.defaultTrackingPower = defaultTrackingPower;
        }
    }

    private final Config config;

    private Info info;
    private MovementController movement;
    private GunController gun;
    private ShotPlanner shotPlanner;
    private BranchedPlanScorer scorer;
    private CandidatePath lastSelectedPath;
    private List<CandidatePath> lastSelectedFamilyPaths;
    private List<PathWaveIntersection> lastSelectedPathIntersections;
    private long lastSelectedWaveDangerRevision;
    private double lastSelectedFirePower;
    private boolean lastSelectedShotWasShadow;
    private double retainedOffensivePower;
    private double offensivePowerBracketMin;
    private double offensivePowerBracketMax;
    private boolean offensivePowerBracketValid;

    public ScoreMaxPlanner() {
        this(Config.DEFAULT);
    }

    public ScoreMaxPlanner(Config config) {
        if (config == null) {
            throw new IllegalArgumentException("ScoreMaxPlanner requires a non-null config");
        }
        this.config = config;
    }

    public void init(Info info, MovementController movement, GunController gun) {
        if (info == null || movement == null || gun == null) {
            throw new IllegalArgumentException("ScoreMaxPlanner requires non-null info, movement, and gun");
        }
        this.info = info;
        this.movement = movement;
        this.gun = gun;
        if (this.scorer == null) {
            this.scorer = new BranchedPlanScorer();
        }
        this.scorer.init(info);
        this.shotPlanner = new ShotPlanner(
                movement,
                gun,
                (path, pathIntersections, selection, currentOurEnergy) -> scorer.scoreShotSelection(
                        path,
                        pathIntersections,
                        selection,
                        currentOurEnergy),
                config.defaultTrackingPower);
        this.lastSelectedPath = null;
        this.lastSelectedFamilyPaths = new ArrayList<>();
        this.lastSelectedPathIntersections = new ArrayList<>();
        this.lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();
        this.lastSelectedFirePower = 0.0;
        this.lastSelectedShotWasShadow = false;
        this.retainedOffensivePower = config.defaultTrackingPower;
        this.offensivePowerBracketMin = Double.NaN;
        this.offensivePowerBracketMax = Double.NaN;
        this.offensivePowerBracketValid = false;
    }

    private static class FiringResult {
        private final double firstTickFirePower;
        private final List<PathWaveIntersection> pathIntersections;
        private final ShotPlanner.ShotSelection shotSelection;
        private final boolean valid;

        private FiringResult(double firstTickFirePower,
                             List<PathWaveIntersection> pathIntersections,
                             ShotPlanner.ShotSelection shotSelection,
                             boolean valid) {
            this.firstTickFirePower = firstTickFirePower;
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

    private static class PlanEvaluation {
        private final BattlePlan plan;
        private final CandidatePath path;
        private final List<PathWaveIntersection> pathIntersections;
        private final ShotPlanner.ShotSelection shotSelection;
        private final double score;
        private final double absoluteScore;
        private final boolean selectedShotWasShadow;

        private PlanEvaluation(BattlePlan plan,
                               CandidatePath path,
                               List<PathWaveIntersection> pathIntersections,
                               ShotPlanner.ShotSelection shotSelection,
                               double score,
                               double absoluteScore,
                               boolean selectedShotWasShadow) {
            this.plan = plan;
            this.path = path;
            this.pathIntersections = pathIntersections;
            this.shotSelection = shotSelection;
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
        if (firstFiringTickOffset >= pathLength) {
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
        PlanningBranch activeBranch = branch != null
                ? branch
                : createOffensivePowerBranch(BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER);
        FireWindowContext activeFireWindow = fireWindowContext != null && fireWindowContext.path == path
                ? fireWindowContext
                : buildFireWindowContext(path, firstFiringTickOffset, currentOurEnergy, pathIntersectionContext);
        List<PathWaveIntersection> pathIntersections = activeFireWindow.pathIntersections;
        double firstTickFirePower = 0;

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

            // NOTE: Power selection currently happens after movement path generation.
            // Outgoing bullet-shadow survival is modeled here, but path danger
            // itself is still based on the precomputed movement evaluation.
            ShotPlanner.ShotSelection shotSelection = shotPlanner.chooseBestShot(
                    path,
                    state.x, state.y, activeFireWindow.enemyAtFireTime,
                    activeFireWindow.expectedBulletDamageTaken, activeFireWindow.expectedEnemyEnergyGain,
                    activeFireWindow.availableEnergy, currentGunHeading,
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
            }
        }

        if (valid && !selectedShot.hasFiniteScore()) {
            selectedShot = selectedShot.withScore(
                    scorer.scoreShotSelection(path, pathIntersections, selectedShot, currentOurEnergy));
        }

        return new FiringResult(
                firstTickFirePower,
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

    public CandidatePath getLastSelectedPath() {
        return lastSelectedPath;
    }

    public List<PathWaveIntersection> getLastSelectedPathIntersections() {
        return lastSelectedPathIntersections;
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
        if (allowsShadowShots()
                && lastSelectedShotWasShadow
                && Math.abs(lastSelectedFirePower - ShotPlanner.SHADOW_FIRE_POWER) <= POWER_SAMPLE_EPSILON) {
            return FirstPassShotMode.SHADOW;
        }
        return FirstPassShotMode.OFFENSIVE;
    }

    private static boolean firstPassIncludesOffensiveShot(FirstPassShotMode shotMode) {
        return shotMode == FirstPassShotMode.OFFENSIVE;
    }

    private boolean usesFixedOffensivePowers() {
        return config.fixedCandidatePowers != null && config.fixedCandidatePowers.length > 0;
    }

    private boolean allowsShadowShots() {
        return config.allowShadow;
    }

    private static double nearestAvailablePower(List<Double> candidatePowers,
                                                double preferredPower) {
        if (candidatePowers == null || candidatePowers.isEmpty()) {
            return 0.0;
        }
        double bestPower = candidatePowers.get(0);
        double bestDistance = Math.abs(bestPower - preferredPower);
        for (double candidatePower : candidatePowers) {
            double distance = Math.abs(candidatePower - preferredPower);
            if (distance < bestDistance - POWER_SAMPLE_EPSILON
                    || (Math.abs(distance - bestDistance) <= POWER_SAMPLE_EPSILON
                    && candidatePower > bestPower)) {
                bestPower = candidatePower;
                bestDistance = distance;
            }
        }
        return bestPower;
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
        if (usesFixedOffensivePowers()) {
            List<Double> candidatePowers = buildCandidateOffensivePowers(maxPower);
            if (candidatePowers.isEmpty()) {
                return 0.0;
            }
            double preferredPower = retainedOffensivePower >= ShotPlanner.MIN_FIRE_POWER
                    ? retainedOffensivePower
                    : config.defaultTrackingPower;
            return nearestAvailablePower(candidatePowers, preferredPower);
        }
        if (retainedOffensivePower >= ShotPlanner.MIN_FIRE_POWER) {
            return Math.min(maxPower, retainedOffensivePower);
        }
        return Math.min(maxPower, config.defaultTrackingPower);
    }

    private static void appendUniquePower(List<Double> powers, double power) {
        for (double existing : powers) {
            if (Math.abs(existing - power) <= POWER_SAMPLE_EPSILON) {
                return;
            }
        }
        powers.add(power);
    }

    private double maxCandidateOffensivePower(double availableEnergy,
                                              double enemyEnergyForScoring) {
        if (!(availableEnergy >= ShotPlanner.MIN_FIRE_POWER)) {
            return 0.0;
        }
        double maxPower = Math.min(ShotPlanner.MAX_FIRE_POWER, availableEnergy);
        if (config.capByEnemyEnergy) {
            maxPower = Math.min(maxPower, PhysicsUtil.requiredBulletPowerForDamage(enemyEnergyForScoring));
        }
        return maxPower;
    }

    private List<Double> buildCandidateOffensivePowers(double maxPower) {
        List<Double> powers = new ArrayList<>();
        if (!(maxPower >= ShotPlanner.MIN_FIRE_POWER)) {
            return powers;
        }
        if (usesFixedOffensivePowers()) {
            for (double configuredPower : config.fixedCandidatePowers) {
                if (!(configuredPower >= ShotPlanner.MIN_FIRE_POWER)
                        || !(configuredPower <= ShotPlanner.MAX_FIRE_POWER)
                        || configuredPower > maxPower + POWER_SAMPLE_EPSILON) {
                    continue;
                }
                appendUniquePower(powers, configuredPower);
            }
            return powers;
        }
        if (Math.abs(maxPower - ShotPlanner.MIN_FIRE_POWER) <= POWER_SAMPLE_EPSILON) {
            powers.add(ShotPlanner.MIN_FIRE_POWER);
            return powers;
        }
        for (int i = 0; i < BotConfig.ScoreMax.GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT; i++) {
            double fraction = i / (double) (BotConfig.ScoreMax.GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT - 1);
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
                optimalGunTurnFromHeading(BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER, gunHeading),
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
                        robotState.x,
                        robotState.y,
                        robotState.heading,
                        robotState.velocity,
                        path.firstTargetX,
                        path.firstTargetY,
                        PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                        PhysicsUtil.SteeringMode.DIRECT,
                        info.getBattlefieldWidth(),
                        info.getBattlefieldHeight())
                : PhysicsUtil.computeMovementInstruction(
                        robotState.x,
                        robotState.y,
                        robotState.heading,
                        robotState.velocity,
                        path.segmentLegs.get(0).targetX,
                        path.segmentLegs.get(0).targetY,
                        PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                        path.segmentLegs.get(0).steeringMode,
                        info.getBattlefieldWidth(),
                        info.getBattlefieldHeight());

        BattlePlan plan = new BattlePlan(
                instruction[0], instruction[1],
                Double.NaN, firingResult.firstTickFirePower);

        return new PlanEvaluation(
                plan,
                path,
                firingResult.pathIntersections,
                firingResult.shotSelection,
                firingResult.shotSelection.score,
                firingResult.shotSelection.absoluteScore,
                firingResult.shotSelection.shadowShot);
    }

    private double[] movementInstructionForPath(CandidatePath path,
                                                RobotSnapshot robotState) {
        return path.segmentLegs.isEmpty()
                ? PhysicsUtil.computeMovementInstruction(
                        robotState.x,
                        robotState.y,
                        robotState.heading,
                        robotState.velocity,
                        path.firstTargetX,
                        path.firstTargetY,
                        PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                        PhysicsUtil.SteeringMode.DIRECT,
                        info.getBattlefieldWidth(),
                        info.getBattlefieldHeight())
                : PhysicsUtil.computeMovementInstruction(
                        robotState.x,
                        robotState.y,
                        robotState.heading,
                        robotState.velocity,
                        path.segmentLegs.get(0).targetX,
                        path.segmentLegs.get(0).targetY,
                        PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                        path.segmentLegs.get(0).steeringMode,
                        info.getBattlefieldWidth(),
                        info.getBattlefieldHeight());
    }

    private double resolveSelectedGunTurn(PlanEvaluation evaluation,
                                          int firstFiringTickOffset,
                                          double currentGunHeading) {
        if (evaluation == null || evaluation.path == null) {
            return optimalGunTurnFromHeading(BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER, currentGunHeading);
        }
        if (evaluation.shotSelection != null && Double.isFinite(evaluation.shotSelection.firingAngle)) {
            return MathUtils.normalizeAngle(evaluation.shotSelection.firingAngle - currentGunHeading);
        }

        CandidatePath path = evaluation.path;
        double aimPower = evaluation.plan != null && evaluation.plan.firePower >= ShotPlanner.MIN_FIRE_POWER
                ? evaluation.plan.firePower
                : BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER;
        if (path.trajectory == null || path.trajectory.length() == 0) {
            return optimalGunTurnFromHeading(aimPower, currentGunHeading);
        }

        int fireStateIndex = Math.min(firstFiringTickOffset, Math.max(0, path.trajectory.length() - 1));
        PhysicsUtil.PositionState fireState = path.trajectory.stateAt(fireStateIndex);
        long fireTime = path.startTime + Math.max(0, firstFiringTickOffset);
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyPositionAt(fireTime);
        if (enemyAtFireTime == null) {
            return optimalGunTurnFromHeading(aimPower, currentGunHeading);
        }

        ShotSolution trackingShot = gun.selectOptimalUnconstrainedShotFromPosition(
                fireState.x,
                fireState.y,
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                aimPower,
                0);
        return Double.isFinite(trackingShot.firingAngle)
                ? MathUtils.normalizeAngle(trackingShot.firingAngle - currentGunHeading)
                : optimalGunTurnFromHeading(aimPower, currentGunHeading);
    }

    private BattlePlan finalizeSelectedPlan(PlanEvaluation evaluation,
                                            RobotSnapshot robotState,
                                            int firstFiringTickOffset,
                                            double currentGunHeading) {
        if (evaluation == null || evaluation.path == null || evaluation.plan == null) {
            return null;
        }
        double[] instruction = movementInstructionForPath(evaluation.path, robotState);
        return new BattlePlan(
                instruction[0],
                instruction[1],
                resolveSelectedGunTurn(evaluation, firstFiringTickOffset, currentGunHeading),
                evaluation.plan.firePower);
    }

    public BattlePlan getBestPlan() {
        scorer.noteScoreMaxRoundSelection();
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
            lastSelectedPathIntersections = new ArrayList<>();
            scorer.clearScoreContext();
            return disabledRamPlan;
        }

        double currentGunHeat = robotState.gunHeat;
        int firstFiringTickOffset = firstFiringTickOffset(currentGunHeat);
        double currentOurEnergy = robotState.energy;
        double currentGunHeading = robotState.gunHeading;
        scorer.prepareScoreContext(robotState, shotPlanner.estimateContinuationFirePower(robotState.energy));
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
            if (!usesFixedOffensivePowers()) {
                ensureOffensivePowerBracket(globalOffensivePowers, maxOffensivePower);
                appendBracketEndpointPowers(offensivePowers, maxOffensivePower);
            }
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
            if (allowsShadowShots() && bestPathFirstPassShotMode != FirstPassShotMode.SHADOW) {
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
                if (!usesFixedOffensivePowers()) {
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
                    optimalGunTurnFromHeading(BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER, currentGunHeading),
                    0.0);
        }

        BattlePlan bestPlan = finalizeSelectedPlan(
                selection.bestEvaluation,
                robotState,
                firstFiringTickOffset,
                currentGunHeading);
        if (bestPlan == null) {
            bestPlan = selection.bestPlan;
        }
        lastSelectedPath = selection.bestPath;
        lastSelectedFamilyPaths = selectTopDistinctFamilyPaths(
                firstPassEvaluations,
                BotConfig.ScoreMax.MAX_CARRIED_FORWARD_FAMILIES);
        lastSelectedPathIntersections = selection.bestPathIntersections;
        lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();
        lastSelectedFirePower = bestPlan.firePower;
        lastSelectedShotWasShadow = selection.bestShotWasShadow;
        scorer.clearScoreContext();
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
        lastSelectedPathIntersections = new ArrayList<>();
        lastSelectedWaveDangerRevision = currentRevision;
    }

    public String describeSkippedTurnDiagnostics() {
        return movement != null
                ? movement.describeLatestPathPlanningDiagnostics()
                : "planning=n/a";
    }
}
