package oog.mega.saguaro.mode.perfectprediction;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import robocode.Rules;
import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.NoOpLearningProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.movement.MovementController;
import oog.mega.saguaro.movement.PathLeg;
import oog.mega.saguaro.movement.PredictedOpponentState;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.mode.scripted.CommittedWaypointPlan;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;
import oog.mega.saguaro.mode.scripted.ScriptedWaypoint;
import oog.mega.saguaro.render.PathOverlay;
import oog.mega.saguaro.render.RenderState;

/**
 * This mode is designed to take advantage of any bot whose movement is purely a function of the current state of the battle, and for whom we can
 * identify that function. This mode's movement is similar to ScoreMaxMode, except instead of changing plans every tick we commit to a future path
 * and do not deviate once it is selected. This allows us to perfectly simulate our opponent's movement and get near-100% targeting accuracy.
 *
 * Uses a committed + tentative path model: committed waypoints are stable and long enough for targeting;
 * a tentative tail beyond them is re-evaluated every tick for safety using random-family path generation
 * with per-candidate opponent simulation (since the opponent is reactive/mirror).
 */

public final class PerfectPredictionMode implements BattleMode {
    private static final int MAX_SEGMENT_ADVANCES_PER_TICK = 64;
    private static final int MAX_TAIL_EXTENSION_ATTEMPTS = 5;
    private static final int TAIL_EXTENSION_STEP_TICKS = 12;
    private static final int OPENING_APPROACH_ANGLE_SAMPLES = 24;
    private static final double[] OPENING_APPROACH_RADIUS_FRACTIONS = new double[]{0.35, 0.7, 1.0};
    private static final double OPENING_APPROACH_MAX_SPEED = 8.0;
    // Gun cooldown at the configured firepower, scaled up to leave extra planning margin.
    private static final int MIN_TAIL_DURATION_TICKS = (int) Math.ceil(
            (1.0 + BotConfig.PerfectPrediction.FIRE_POWER / 5.0)
                    / 0.1
                    * BotConfig.PerfectPrediction.TAIL_DURATION_GUN_HEAT_MARGIN);
    private static final Color OUR_PATH_COLOR = new Color(40, 200, 255);
    private static final Color TENTATIVE_TAIL_COLOR = new Color(40, 200, 255, 100);
    private static final Color OPPONENT_PATH_COLOR = new Color(255, 80, 80);
    private static final float PATH_STROKE_WIDTH = 2.0f;

    private final RoundOutcomeProfile roundOutcomeProfile = NoOpLearningProfile.INSTANCE;
    private final List<ScriptedWaypoint> waypoints = new ArrayList<ScriptedWaypoint>();
    private final List<Long> committedShotImpactTimes = new ArrayList<Long>();

    private Info info;
    private MovementController movement;
    private long planStartTime;
    private List<PathLeg> tentativeTailLegs = Collections.emptyList();
    private List<PathLeg> rememberedTentativeTailLegs = Collections.emptyList();
    private long rememberedTentativeTailTime = Long.MIN_VALUE;
    private int rememberedTentativeTailCommittedPrefixTicks;
    private boolean openingApproachResolvedThisRound;
    private boolean openingShotFiredThisRound;
    private OpponentDriveSimulator.AimSolution lastAimSolution;
    private PhysicsUtil.Trajectory lastPlannedTrajectory;
    private PhysicsUtil.Trajectory lastTentativeTailTrajectory;
    private PhysicsUtil.Trajectory lastPredictedOpponentTrajectory;
    private final ActiveLegParkingTracker activeLegParkingTracker = new ActiveLegParkingTracker();

    @Override
    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfile;
    }

    @Override
    public ModeObservationPolicy getObservationPolicy() {
        return ModeObservationPolicy.COLLECT_ONLY;
    }

    @Override
    public void init(Info info, BattleServices services) {
        if (info == null) {
            throw new IllegalArgumentException("PerfectPredictionMode requires non-null info");
        }
        if (services == null || services.movement() == null) {
            throw new IllegalArgumentException("PerfectPredictionMode requires non-null movement services");
        }
        this.info = info;
        this.movement = services.movement();
        this.planStartTime = Long.MIN_VALUE;
        this.waypoints.clear();
        this.committedShotImpactTimes.clear();
        this.tentativeTailLegs = Collections.emptyList();
        this.rememberedTentativeTailLegs = Collections.emptyList();
        this.rememberedTentativeTailTime = Long.MIN_VALUE;
        this.rememberedTentativeTailCommittedPrefixTicks = 0;
        this.openingApproachResolvedThisRound = false;
        this.openingShotFiredThisRound = false;
        this.lastAimSolution = null;
        this.lastPlannedTrajectory = null;
        this.lastTentativeTailTrajectory = null;
        this.lastPredictedOpponentTrajectory = null;
        this.activeLegParkingTracker.reset();
    }

    @Override
    public BattlePlan getPlan() {
        if (info == null) {
            throw new IllegalStateException("PerfectPredictionMode must be initialized before planning");
        }

        RobotSnapshot myNow = info.captureRobotSnapshot();
        EnemyInfo enemy = info.getEnemy();
        BattlePlan preScanOpeningPlan = buildPreScanOpeningPlan(myNow, enemy);
        if (preScanOpeningPlan != null) {
            lastAimSolution = null;
            lastPlannedTrajectory = null;
            lastTentativeTailTrajectory = null;
            lastPredictedOpponentTrajectory = null;
            activeLegParkingTracker.reset();
            return preScanOpeningPlan;
        }

        ReactiveOpponentPredictor predictor = currentPredictor();
        advanceWaypointQueue(myNow);
        tentativeTailLegs = currentRememberedTentativeTailLegs(myNow.time);
        pruneExpiredCommittedShotImpactTimes(myNow.time);
        rebalanceCommittedPath(myNow, requiredCommittedTicks(myNow));

        BattlePlan ramPlan = buildDisabledRamPlan(myNow);
        if (ramPlan != null) {
            return ramPlan;
        }

        initializeOpeningApproachIfNeeded(myNow, enemy);
        PredictedOpponentState opponentStart = buildOpponentStart(enemy, myNow);
        PlanState planState = currentPlanState(myNow);
        PredictionResult prediction = null;
        PhysicsUtil.Trajectory effectiveTrajectory = planState.ourTrajectory;
        if (enemy != null && enemy.alive && enemy.seenThisRound) {
            if (myNow.gunHeat == 0.0 && !tentativeTailLegs.isEmpty()) {
                List<PathLeg> frozenTailLegs = tentativeTailLegs;
                boolean activeTailParkingPhase = planState.activeWaypoint == null
                        ? updateActiveLegParkingPhase(myNow, frozenTailLegs)
                        : false;
                PhysicsUtil.Trajectory frozenTailTrajectory = simulateTailTrajectory(
                        planState.ourTrajectory, frozenTailLegs, activeTailParkingPhase);
                PhysicsUtil.Trajectory frozenTrajectory = buildEffectiveTrajectory(
                        planState.ourTrajectory, frozenTailTrajectory);
                effectiveTrajectory = frozenTrajectory;
                if (canPredictOpponentFromTrajectory(effectiveTrajectory)) {
                    prediction = predictOpponent(myNow, effectiveTrajectory, predictor);
                }
                List<PathLeg> extensionTailLegs = Collections.emptyList();
                if (prediction == null || !prediction.wavePassed) {
                    int requiredTailTicks = MIN_TAIL_DURATION_TICKS;
                    for (int attempt = 0; attempt < MAX_TAIL_EXTENSION_ATTEMPTS; attempt++) {
                        extensionTailLegs = movement.generateBestRandomTail(
                                frozenTrajectory,
                                myNow.time,
                                opponentStart,
                                predictor,
                                requiredTailTicks,
                                extensionTailLegs);
                        PhysicsUtil.Trajectory extensionTailTrajectory = simulateTailTrajectory(
                                frozenTrajectory,
                                extensionTailLegs,
                                false);
                        effectiveTrajectory = buildEffectiveTrajectory(frozenTrajectory, extensionTailTrajectory);
                        if (canPredictOpponentFromTrajectory(effectiveTrajectory)) {
                            prediction = predictOpponent(myNow, effectiveTrajectory, predictor);
                        }
                        if (prediction != null && prediction.wavePassed) {
                            break;
                        }
                        int currentTailTicks = Math.max(0, effectiveTrajectory.length() - frozenTrajectory.length());
                        requiredTailTicks = Math.max(requiredTailTicks + TAIL_EXTENSION_STEP_TICKS,
                                currentTailTicks + TAIL_EXTENSION_STEP_TICKS);
                    }
                }
                tentativeTailLegs = appendLegs(frozenTailLegs, extensionTailLegs);
                lastTentativeTailTrajectory = simulateTailTrajectory(
                        planState.ourTrajectory, tentativeTailLegs, activeTailParkingPhase);
            } else {
                int requiredTailTicks = MIN_TAIL_DURATION_TICKS;
                for (int attempt = 0; attempt < MAX_TAIL_EXTENSION_ATTEMPTS; attempt++) {
                    tentativeTailLegs = movement.generateBestRandomTail(
                            planState.ourTrajectory,
                            myNow.time,
                            opponentStart,
                            predictor,
                            requiredTailTicks,
                            tentativeTailLegs);
                    boolean activeTailParkingPhase = planState.activeWaypoint == null
                            ? updateActiveLegParkingPhase(myNow, tentativeTailLegs)
                            : false;
                    lastTentativeTailTrajectory = simulateTailTrajectory(
                            planState.ourTrajectory, tentativeTailLegs, activeTailParkingPhase);
                    effectiveTrajectory = buildEffectiveTrajectory(planState.ourTrajectory, lastTentativeTailTrajectory);
                    if (canPredictOpponentFromTrajectory(effectiveTrajectory)) {
                        prediction = predictOpponent(myNow, effectiveTrajectory, predictor);
                    }
                    if (prediction != null && prediction.wavePassed) {
                        break;
                    }
                    int currentTailTicks = Math.max(0, effectiveTrajectory.length() - planState.ourTrajectory.length());
                    requiredTailTicks = Math.max(requiredTailTicks + TAIL_EXTENSION_STEP_TICKS,
                            currentTailTicks + TAIL_EXTENSION_STEP_TICKS);
                }
            }
        } else {
            boolean activeTailParkingPhase = planState.activeWaypoint == null
                    ? updateActiveLegParkingPhase(myNow, tentativeTailLegs)
                    : false;
            lastTentativeTailTrajectory = tentativeTailLegs.isEmpty()
                    ? null
                    : simulateTailTrajectory(planState.ourTrajectory, tentativeTailLegs, activeTailParkingPhase);
        }

        OpponentDriveSimulator.AimSolution aimSolution = prediction != null ? prediction.aimSolution : null;
        double gunTurn = aimSolution != null
                ? MathUtils.normalizeAngle(aimSolution.firingAngle - myNow.gunHeading)
                : 0.0;
        double firePower = prediction != null && prediction.wavePassed && shouldFire(myNow, aimSolution)
                ? BotConfig.PerfectPrediction.FIRE_POWER
                : 0.0;
        if (firePower >= 0.1 && aimSolution != null) {
            openingShotFiredThisRound = true;
            committedShotImpactTimes.add(myNow.time + 1L + aimSolution.interceptTick);
            rebalanceCommittedPath(myNow, requiredCommittedTicks(myNow));
            planState = currentPlanState(myNow);
            if (enemy != null && enemy.alive && enemy.seenThisRound) {
                boolean activeTailParkingPhase = planState.activeWaypoint == null
                        ? updateActiveLegParkingPhase(myNow, tentativeTailLegs)
                        : false;
                lastTentativeTailTrajectory = simulateTailTrajectory(
                        planState.ourTrajectory, tentativeTailLegs, activeTailParkingPhase);
            }
        }

        lastPlannedTrajectory = planState != null ? planState.ourTrajectory : null;
        lastAimSolution = aimSolution;
        lastPredictedOpponentTrajectory = prediction != null ? prediction.opponentTrajectory : null;
        rememberTentativeTailSelection(myNow.time, planState);

        if (planState != null && planState.activeWaypoint != null) {
            return planState.movementPlan.createExecutionPlan(
                    myNow,
                    gunTurn,
                    firePower,
                    planState.activeWaypointParkingPhase);
        }
        if (!tentativeTailLegs.isEmpty()) {
            boolean activeTailParkingPhase = updateActiveLegParkingPhase(myNow, tentativeTailLegs);
            return createExecutionPlanForLeg(
                    myNow,
                    tentativeTailLegs.get(0),
                    activeTailParkingPhase,
                    gunTurn,
                    firePower);
        }
        activeLegParkingTracker.reset();
        return new BattlePlan(0.0, 0.0, gunTurn, firePower);
    }

    @Override
    public RenderState getRenderState() {
        return new RenderState(buildPathOverlays(), false);
    }

    @Override
    public void applyColors(Saguaro robot) {
        robot.setBodyColor(new Color(32, 148, 92));
        robot.setGunColor(new Color(22, 68, 42));
        robot.setRadarColor(new Color(244, 226, 92));
        robot.setBulletColor(new Color(255, 236, 108));
        robot.setScanColor(new Color(112, 244, 182));
    }

    private BattlePlan createExecutionPlanForLeg(RobotSnapshot myNow,
                                                 PathLeg leg,
                                                 boolean activeLegParkingPhase,
                                                 double gunTurn,
                                                 double firePower) {
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                myNow.x,
                myNow.y,
                myNow.heading,
                myNow.velocity,
                leg.targetX,
                leg.targetY,
                PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                leg.steeringMode,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight(),
                activeLegParkingPhase);
        return new BattlePlan(instruction[0], instruction[1], gunTurn, firePower);
    }

    private void pruneExpiredCommittedShotImpactTimes(long currentTime) {
        for (int i = committedShotImpactTimes.size() - 1; i >= 0; i--) {
            if (committedShotImpactTimes.get(i) <= currentTime) {
                committedShotImpactTimes.remove(i);
            }
        }
    }

    private int requiredCommittedTicks(RobotSnapshot myNow) {
        long currentTime = myNow.time;
        long latestImpactTime = currentTime;
        for (Long impactTime : committedShotImpactTimes) {
            if (impactTime != null && impactTime > latestImpactTime) {
                latestImpactTime = impactTime;
            }
        }
        int impactCommitTicks = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, latestImpactTime - currentTime));
        return Math.max(impactCommitTicks,
                Math.max(openingApproachCommitTicks(myNow), openingShotCommitTicks(myNow)));
    }

    private int openingApproachCommitTicks(RobotSnapshot myNow) {
        if (!openingApproachResolvedThisRound || openingShotFiredThisRound) {
            return 0;
        }
        return openingApproachTickBudget(myNow);
    }

    private void initializeOpeningApproachIfNeeded(RobotSnapshot myNow, EnemyInfo enemy) {
        if (openingApproachResolvedThisRound || enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return;
        }
        openingApproachResolvedThisRound = true;
        if (!waypoints.isEmpty() || !tentativeTailLegs.isEmpty()) {
            return;
        }
        int tickBudget = openingApproachTickBudget(myNow);
        if (tickBudget <= 0) {
            return;
        }
        ScriptedWaypoint openingWaypoint = selectOpeningApproachWaypoint(myNow, enemy, tickBudget);
        if (openingWaypoint != null) {
            waypoints.add(openingWaypoint);
        }
    }

    private int openingApproachTickBudget(RobotSnapshot myNow) {
        if (myNow.energy < BotConfig.PerfectPrediction.FIRE_POWER) {
            return 0;
        }
        int ticksUntilGunReady = ticksUntilGunReady(myNow.gunHeat, myNow.gunCoolingRate);
        return Math.max(0, ticksUntilGunReady - BotConfig.PerfectPrediction.OPENING_SHOT_COMMIT_LEAD_TICKS);
    }

    private ScriptedWaypoint selectOpeningApproachWaypoint(RobotSnapshot myNow, EnemyInfo enemy, int tickBudget) {
        double bfWidth = info.getBattlefieldWidth();
        double bfHeight = info.getBattlefieldHeight();
        EnemyInfo.PredictedPosition opponentNow = enemy.predictPositionAtTime(myNow.time, bfWidth, bfHeight);
        double maxRadius = OPENING_APPROACH_MAX_SPEED * tickBudget;

        double bestX = myNow.x;
        double bestY = myNow.y;
        double bestWallDistance = distanceToNearestWall(myNow.x, myNow.y, bfWidth, bfHeight);
        double bestScore = Math.min(
                0.5 * RobotHitbox.minDistance(opponentNow.x, opponentNow.y, myNow.x, myNow.y),
                bestWallDistance);
        double bestTravelDistance = 0.0;

        for (double radiusFraction : OPENING_APPROACH_RADIUS_FRACTIONS) {
            double radius = maxRadius * radiusFraction;
            for (int i = 0; i < OPENING_APPROACH_ANGLE_SAMPLES; i++) {
                double angle = (Math.PI * 2.0 * i) / OPENING_APPROACH_ANGLE_SAMPLES;
                double candidateX = clampToBattlefield(myNow.x + FastTrig.sin(angle) * radius, bfWidth);
                double candidateY = clampToBattlefield(myNow.y + FastTrig.cos(angle) * radius, bfHeight);
                double wallDistance = distanceToNearestWall(candidateX, candidateY, bfWidth, bfHeight);
                double opponentDistance = RobotHitbox.minDistance(opponentNow.x, opponentNow.y, candidateX, candidateY);
                double score = Math.min(0.5 * opponentDistance, wallDistance);
                double travelDistance = Math.hypot(candidateX - myNow.x, candidateY - myNow.y);
                if (score > bestScore + 1e-9
                        || (Math.abs(score - bestScore) <= 1e-9 && wallDistance > bestWallDistance + 1e-9)
                        || (Math.abs(score - bestScore) <= 1e-9
                        && Math.abs(wallDistance - bestWallDistance) <= 1e-9
                        && travelDistance < bestTravelDistance - 1e-9)) {
                    bestScore = score;
                    bestWallDistance = wallDistance;
                    bestTravelDistance = travelDistance;
                    bestX = candidateX;
                    bestY = candidateY;
                }
            }
        }
        return new ScriptedWaypoint(bestX, bestY, tickBudget);
    }

    private int openingShotCommitTicks(RobotSnapshot myNow) {
        if (openingShotFiredThisRound || myNow.energy < BotConfig.PerfectPrediction.FIRE_POWER) {
            return 0;
        }
        int ticksUntilGunReady = ticksUntilGunReady(myNow.gunHeat, myNow.gunCoolingRate);
        if (ticksUntilGunReady <= 0
                || ticksUntilGunReady > BotConfig.PerfectPrediction.OPENING_SHOT_COMMIT_LEAD_TICKS) {
            return 0;
        }
        return ticksUntilGunReady;
    }

    private void rebalanceCommittedPath(RobotSnapshot myNow, int requiredCommittedTicks) {
        List<PathLeg> effectiveLegs = buildEffectiveLegs(myNow);
        List<ScriptedWaypoint> newCommittedWaypoints = new ArrayList<ScriptedWaypoint>();
        List<PathLeg> newTentativeTail = new ArrayList<PathLeg>();
        int remainingCommittedTicks = Math.max(0, requiredCommittedTicks);
        for (PathLeg leg : effectiveLegs) {
            if (remainingCommittedTicks <= 0) {
                newTentativeTail.add(leg);
                continue;
            }
            int committedDuration = Math.min(leg.durationTicks, remainingCommittedTicks);
            newCommittedWaypoints.add(new ScriptedWaypoint(leg.targetX, leg.targetY, committedDuration));
            remainingCommittedTicks -= committedDuration;
            if (committedDuration < leg.durationTicks) {
                newTentativeTail.add(new PathLeg(
                        leg.targetX,
                        leg.targetY,
                        leg.durationTicks - committedDuration,
                        leg.steeringMode));
            }
        }
        waypoints.clear();
        waypoints.addAll(newCommittedWaypoints);
        tentativeTailLegs = newTentativeTail;
        planStartTime = myNow.time;
    }

    private List<PathLeg> buildEffectiveLegs(RobotSnapshot myNow) {
        List<PathLeg> effectiveLegs = new ArrayList<PathLeg>();
        List<ScriptedWaypoint> remainingWaypoints = currentMovementPlan().remainingWaypoints(myNow.time);
        for (ScriptedWaypoint waypoint : remainingWaypoints) {
            effectiveLegs.add(new PathLeg(
                    waypoint.x,
                    waypoint.y,
                    waypoint.durationTicks,
                    PhysicsUtil.SteeringMode.DIRECT));
        }
        effectiveLegs.addAll(tentativeTailLegs);
        return effectiveLegs;
    }

    private static PhysicsUtil.Trajectory buildEffectiveTrajectory(PhysicsUtil.Trajectory committedTrajectory,
                                                                   PhysicsUtil.Trajectory tentativeTailTrajectory) {
        if (tentativeTailTrajectory == null || tentativeTailTrajectory.length() < 2) {
            return committedTrajectory;
        }
        PhysicsUtil.PositionState[] states =
                new PhysicsUtil.PositionState[committedTrajectory.length() + tentativeTailTrajectory.length() - 1];
        System.arraycopy(committedTrajectory.states, 0, states, 0, committedTrajectory.length());
        for (int i = 1; i < tentativeTailTrajectory.length(); i++) {
            states[committedTrajectory.length() + i - 1] = tentativeTailTrajectory.stateAt(i);
        }
        return new PhysicsUtil.Trajectory(states);
    }

    private PredictedOpponentState buildOpponentStart(EnemyInfo enemy, RobotSnapshot myNow) {
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        EnemyInfo.PredictedPosition enemyAtNow = enemy.predictPositionAtTime(
                myNow.time,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return new PredictedOpponentState(
                enemyAtNow.x,
                enemyAtNow.y,
                enemyAtNow.heading,
                enemyAtNow.velocity,
                enemy.gunHeat,
                enemy.energy,
                enemy.lastDetectedBulletPower);
    }

    private void advanceWaypointQueue(RobotSnapshot myNow) {
        long currentTime = myNow.time;
        if (planStartTime == Long.MIN_VALUE) {
            planStartTime = currentTime;
        }
        int advances = 0;
        while (!waypoints.isEmpty()) {
            int elapsedTicks = (int) Math.max(0L, currentTime - planStartTime);
            if (elapsedTicks < waypoints.get(0).durationTicks) {
                return;
            }
            if (++advances > MAX_SEGMENT_ADVANCES_PER_TICK) {
                throw new IllegalStateException(
                        "Perfect-prediction waypoint queue advanced too many segments in one tick");
            }
            ScriptedWaypoint completed = waypoints.remove(0);
            planStartTime += completed.durationTicks;
        }
    }

    private PredictionResult predictOpponent(RobotSnapshot myNow,
                                             PhysicsUtil.Trajectory ourTrajectory,
                                             ReactiveOpponentPredictor predictor) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }

        PhysicsUtil.PositionState shooterState = ourTrajectory.stateAt(1);
        EnemyInfo.PredictedPosition enemyAtFireTime = enemy.predictPositionAtTime(
                myNow.time + 1L,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        PhysicsUtil.PositionState enemyAtFire = new PhysicsUtil.PositionState(
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                enemyAtFireTime.heading,
                enemyAtFireTime.velocity);
        PhysicsUtil.Trajectory firingTrajectory = trajectoryFromOffset(ourTrajectory, 1);
        PhysicsUtil.Trajectory opponentTrajectory = OpponentDriveSimulator.simulateTrajectory(
                enemyAtFire,
                firingTrajectory,
                predictor,
                firingTrajectory.length() - 1,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        boolean wavePassed = waveHasFullyPassedOpponent(
                shooterState.x,
                shooterState.y,
                Wave.bulletSpeed(BotConfig.PerfectPrediction.FIRE_POWER),
                opponentTrajectory);
        OpponentDriveSimulator.AimSolution aimSolution = OpponentDriveSimulator.solveInterceptFromTrajectory(
                shooterState.x,
                shooterState.y,
                Wave.bulletSpeed(BotConfig.PerfectPrediction.FIRE_POWER),
                opponentTrajectory);
        PhysicsUtil.Trajectory visibleTrajectory = aimSolution != null
                ? truncateTrajectory(opponentTrajectory, aimSolution.interceptTick)
                : opponentTrajectory;
        return new PredictionResult(aimSolution, visibleTrajectory, wavePassed);
    }

    private BattlePlan buildDisabledRamPlan(RobotSnapshot myNow) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound || enemy.energy > 0.0) {
            return null;
        }
        if (hasActiveRealEnemyWave(myNow.x, myNow.y, myNow.time)) {
            return null;
        }
        if (!committedShotImpactTimes.isEmpty()) {
            return null;
        }
        long nextTick = myNow.time + 1;
        EnemyInfo.PredictedPosition predictedEnemy = enemy.predictPositionAtTime(
                nextTick, info.getBattlefieldWidth(), info.getBattlefieldHeight());
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                myNow.x, myNow.y, myNow.heading, myNow.velocity,
                predictedEnemy.x, predictedEnemy.y);
        return new BattlePlan(instruction[0], instruction[1], 0.0, 0.0);
    }

    private BattlePlan buildPreScanOpeningPlan(RobotSnapshot myNow, EnemyInfo enemy) {
        if (enemy != null && enemy.alive && enemy.seenThisRound) {
            return null;
        }
        double centerX = info.getBattlefieldWidth() * 0.5;
        double centerY = info.getBattlefieldHeight() * 0.5;
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                myNow.x,
                myNow.y,
                myNow.heading,
                myNow.velocity,
                centerX,
                centerY);
        return new BattlePlan(instruction[0], instruction[1], 0.0, 0.0);
    }

    private boolean hasActiveRealEnemyWave(double x, double y, long currentTime) {
        for (Wave wave : info.getEnemyWaves()) {
            if (!wave.hasPassed(x, y, currentTime)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldFire(RobotSnapshot myNow, OpponentDriveSimulator.AimSolution aimSolution) {
        return aimSolution != null
                && myNow.energy >= BotConfig.PerfectPrediction.FIRE_POWER
                && myNow.gunHeat == 0.0;
    }

    private List<PathOverlay> buildPathOverlays() {
        List<PathOverlay> overlays = new ArrayList<PathOverlay>(3);
        appendPathOverlay(overlays, lastPlannedTrajectory, OUR_PATH_COLOR);
        appendPathOverlay(overlays, lastTentativeTailTrajectory, TENTATIVE_TAIL_COLOR);
        appendPathOverlay(overlays, lastPredictedOpponentTrajectory, OPPONENT_PATH_COLOR);
        return overlays;
    }

    private static void appendPathOverlay(List<PathOverlay> overlays,
                                          PhysicsUtil.Trajectory trajectory,
                                          Color color) {
        if (trajectory == null || trajectory.length() < 2) {
            return;
        }
        overlays.add(PathOverlay.forTrajectory(trajectory, color, PATH_STROKE_WIDTH));
    }

    private PhysicsUtil.Trajectory simulateTailTrajectory(PhysicsUtil.Trajectory committedTrajectory,
                                                          List<PathLeg> tailLegs,
                                                          boolean activeLegEndpointPhase) {
        if (tailLegs == null || tailLegs.isEmpty() || committedTrajectory == null || committedTrajectory.length() < 1) {
            return null;
        }
        double bfWidth = info.getBattlefieldWidth();
        double bfHeight = info.getBattlefieldHeight();
        PhysicsUtil.PositionState currentState = committedTrajectory.stateAt(committedTrajectory.length() - 1);
        long currentTime = planStartTime + committedTrajectory.length() - 1L;
        List<PhysicsUtil.PositionState> states = new ArrayList<>();
        states.add(currentState);
        for (PathLeg leg : tailLegs) {
            PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                    currentState,
                    leg.targetX,
                    leg.targetY,
                    currentTime,
                    null,
                    currentTime + leg.durationTicks,
                    PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                    leg.steeringMode,
                    bfWidth,
                    bfHeight,
                    states.size() == 1 && activeLegEndpointPhase);
            for (int i = 1; i < segment.states.length; i++) {
                states.add(segment.states[i]);
            }
            int segmentTicks = segment.length() - 1;
            currentState = segment.stateAt(segmentTicks);
            currentTime += segmentTicks;
        }
        return states.size() >= 2
                ? new PhysicsUtil.Trajectory(states.toArray(new PhysicsUtil.PositionState[0]))
                : null;
    }

    private static PhysicsUtil.Trajectory trajectoryFromOffset(PhysicsUtil.Trajectory trajectory, int startOffset) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory slicing requires a non-null trajectory");
        }
        int offset = Math.max(0, startOffset);
        int remainingLength = Math.max(1, trajectory.length() - offset);
        PhysicsUtil.PositionState[] states = new PhysicsUtil.PositionState[remainingLength];
        for (int i = 0; i < remainingLength; i++) {
            states[i] = trajectory.stateAt(offset + i);
        }
        return new PhysicsUtil.Trajectory(states);
    }

    private static PhysicsUtil.Trajectory truncateTrajectory(PhysicsUtil.Trajectory trajectory, int endTickInclusive) {
        if (trajectory == null) {
            throw new IllegalArgumentException("Trajectory truncation requires a non-null trajectory");
        }
        int endIndex = Math.max(0, Math.min(endTickInclusive, trajectory.length() - 1));
        PhysicsUtil.PositionState[] states = new PhysicsUtil.PositionState[endIndex + 1];
        for (int i = 0; i <= endIndex; i++) {
            states[i] = trajectory.stateAt(i);
        }
        return new PhysicsUtil.Trajectory(states);
    }

    private ReactiveOpponentPredictor currentPredictor() {
        ReactiveOpponentPredictor predictor = info.createPerfectPredictionPredictor();
        if (predictor == null) {
            throw new IllegalStateException("PerfectPrediction mode requires a non-null predictor");
        }
        return predictor;
    }

    private CommittedWaypointPlan currentMovementPlan() {
        return new CommittedWaypointPlan(
                planStartTime,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight(),
                waypoints);
    }

    private static boolean canPredictOpponentFromTrajectory(PhysicsUtil.Trajectory trajectory) {
        return trajectory != null && trajectory.length() >= 3;
    }

    private static double distanceToNearestWall(double x, double y, double battlefieldWidth, double battlefieldHeight) {
        double margin = RobotHitbox.HALF_WIDTH;
        return Math.min(
                Math.min(x - margin, battlefieldWidth - margin - x),
                Math.min(y - margin, battlefieldHeight - margin - y));
    }

    private static double clampToBattlefield(double value, double battlefieldExtent) {
        double margin = RobotHitbox.HALF_WIDTH;
        return Math.max(margin, Math.min(battlefieldExtent - margin, value));
    }

    private static int ticksUntilGunReady(double gunHeat, double gunCoolingRate) {
        if (gunHeat <= 0.0) {
            return 0;
        }
        return (int) Math.ceil(gunHeat / gunCoolingRate);
    }

    private List<PathLeg> currentRememberedTentativeTailLegs(long currentTime) {
        if (rememberedTentativeTailTime == Long.MIN_VALUE || rememberedTentativeTailLegs.isEmpty()) {
            return Collections.emptyList();
        }
        long elapsedTicksLong = Math.max(0L, currentTime - rememberedTentativeTailTime);
        int elapsedTicks = (int) Math.min(Integer.MAX_VALUE, elapsedTicksLong);
        int consumedTailTicks = Math.max(0, elapsedTicks - rememberedTentativeTailCommittedPrefixTicks);
        return trimConsumedLegs(rememberedTentativeTailLegs, consumedTailTicks);
    }

    private void rememberTentativeTailSelection(long currentTime, PlanState planState) {
        if (planState == null || tentativeTailLegs == null || tentativeTailLegs.isEmpty()) {
            rememberedTentativeTailLegs = Collections.emptyList();
            rememberedTentativeTailTime = Long.MIN_VALUE;
            rememberedTentativeTailCommittedPrefixTicks = 0;
            return;
        }
        rememberedTentativeTailLegs = copyLegs(tentativeTailLegs);
        rememberedTentativeTailTime = currentTime;
        rememberedTentativeTailCommittedPrefixTicks = Math.max(0, planState.ourTrajectory.length() - 1);
    }

    private static List<PathLeg> copyLegs(List<PathLeg> sourceLegs) {
        if (sourceLegs == null || sourceLegs.isEmpty()) {
            return Collections.emptyList();
        }
        List<PathLeg> copied = new ArrayList<PathLeg>(sourceLegs.size());
        for (PathLeg leg : sourceLegs) {
            copied.add(new PathLeg(leg.targetX, leg.targetY, leg.durationTicks, leg.steeringMode));
        }
        return copied;
    }

    private static List<PathLeg> appendLegs(List<PathLeg> firstLegs, List<PathLeg> secondLegs) {
        if (firstLegs == null || firstLegs.isEmpty()) {
            return copyLegs(secondLegs);
        }
        if (secondLegs == null || secondLegs.isEmpty()) {
            return copyLegs(firstLegs);
        }
        List<PathLeg> combined = new ArrayList<PathLeg>(firstLegs.size() + secondLegs.size());
        combined.addAll(firstLegs);
        combined.addAll(secondLegs);
        return combined;
    }

    private static List<PathLeg> trimConsumedLegs(List<PathLeg> sourceLegs, int elapsedTicks) {
        if (sourceLegs == null || sourceLegs.isEmpty()) {
            return Collections.emptyList();
        }
        int remainingTicks = Math.max(0, elapsedTicks);
        boolean keepingRemainder = false;
        List<PathLeg> remainingLegs = new ArrayList<PathLeg>(sourceLegs.size());
        for (PathLeg leg : sourceLegs) {
            if (keepingRemainder) {
                remainingLegs.add(leg);
                continue;
            }
            if (remainingTicks >= leg.durationTicks) {
                remainingTicks -= leg.durationTicks;
                continue;
            }
            remainingLegs.add(new PathLeg(
                    leg.targetX,
                    leg.targetY,
                    leg.durationTicks - remainingTicks,
                    leg.steeringMode));
            remainingTicks = 0;
            keepingRemainder = true;
        }
        return remainingLegs.isEmpty() ? Collections.<PathLeg>emptyList() : remainingLegs;
    }

    private PlanState currentPlanState(RobotSnapshot myNow) {
        CommittedWaypointPlan movementPlan = currentMovementPlan();
        ScriptedWaypoint activeWaypoint = movementPlan.activeWaypoint(myNow.time);
        boolean activeWaypointParkingPhase = updateActiveLegParkingPhase(myNow, activeWaypoint);
        return new PlanState(
                movementPlan,
                activeWaypoint,
                activeWaypointParkingPhase,
                movementPlan.simulateRemainingTrajectory(myNow, activeWaypointParkingPhase));
    }

    private boolean updateActiveLegParkingPhase(RobotSnapshot myNow, ScriptedWaypoint activeWaypoint) {
        if (activeWaypoint == null) {
            activeLegParkingTracker.reset();
            return false;
        }
        return activeLegParkingTracker.update(
                myNow,
                activeWaypoint.x,
                activeWaypoint.y,
                PhysicsUtil.SteeringMode.DIRECT);
    }

    private boolean updateActiveLegParkingPhase(RobotSnapshot myNow, List<PathLeg> tailLegs) {
        if (tailLegs == null || tailLegs.isEmpty()) {
            activeLegParkingTracker.reset();
            return false;
        }
        PathLeg leg = tailLegs.get(0);
        return activeLegParkingTracker.update(myNow, leg.targetX, leg.targetY, leg.steeringMode);
    }

    private static boolean waveHasFullyPassedOpponent(double shooterX,
                                                      double shooterY,
                                                      double bulletSpeed,
                                                      PhysicsUtil.Trajectory opponentTrajectory) {
        if (opponentTrajectory == null || opponentTrajectory.length() < 2) {
            return false;
        }
        if (bulletSpeed <= Rules.MAX_VELOCITY) {
            return false;
        }
        int flightTicks = opponentTrajectory.length() - 1;
        PhysicsUtil.PositionState lastState = opponentTrajectory.stateAt(flightTicks);
        double innerRadius = bulletSpeed * (flightTicks - 1);
        return innerRadius > RobotHitbox.maxDistance(shooterX, shooterY, lastState.x, lastState.y);
    }

    private static final class PlanState {
        final CommittedWaypointPlan movementPlan;
        final ScriptedWaypoint activeWaypoint;
        final boolean activeWaypointParkingPhase;
        final PhysicsUtil.Trajectory ourTrajectory;

        PlanState(CommittedWaypointPlan movementPlan,
                  ScriptedWaypoint activeWaypoint,
                  boolean activeWaypointParkingPhase,
                  PhysicsUtil.Trajectory ourTrajectory) {
            this.movementPlan = movementPlan;
            this.activeWaypoint = activeWaypoint;
            this.activeWaypointParkingPhase = activeWaypointParkingPhase;
            this.ourTrajectory = ourTrajectory;
        }
    }

    private static final class ActiveLegParkingTracker {
        private boolean latched;
        private double targetX = Double.NaN;
        private double targetY = Double.NaN;
        private PhysicsUtil.SteeringMode steeringMode;

        boolean update(RobotSnapshot currentState,
                       double nextTargetX,
                       double nextTargetY,
                       PhysicsUtil.SteeringMode nextSteeringMode) {
            if (!matches(nextTargetX, nextTargetY, nextSteeringMode)) {
                latched = false;
                targetX = nextTargetX;
                targetY = nextTargetY;
                steeringMode = nextSteeringMode;
            }
            latched = PhysicsUtil.updateParkAndWaitPhase(
                    currentState.x,
                    currentState.y,
                    currentState.heading,
                    currentState.velocity,
                    targetX,
                    targetY,
                    latched);
            return latched;
        }

        void reset() {
            latched = false;
            targetX = Double.NaN;
            targetY = Double.NaN;
            steeringMode = null;
        }

        private boolean matches(double nextTargetX,
                                double nextTargetY,
                                PhysicsUtil.SteeringMode nextSteeringMode) {
            return steeringMode == nextSteeringMode
                    && Double.doubleToLongBits(targetX) == Double.doubleToLongBits(nextTargetX)
                    && Double.doubleToLongBits(targetY) == Double.doubleToLongBits(nextTargetY);
        }
    }

    private static final class PredictionResult {
        final OpponentDriveSimulator.AimSolution aimSolution;
        final PhysicsUtil.Trajectory opponentTrajectory;
        final boolean wavePassed;

        PredictionResult(OpponentDriveSimulator.AimSolution aimSolution,
                         PhysicsUtil.Trajectory opponentTrajectory,
                         boolean wavePassed) {
            this.aimSolution = aimSolution;
            this.opponentTrajectory = opponentTrajectory;
            this.wavePassed = wavePassed;
        }
    }
}
