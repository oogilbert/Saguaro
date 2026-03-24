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
    private OpponentDriveSimulator.AimSolution lastAimSolution;
    private PhysicsUtil.Trajectory lastPlannedTrajectory;
    private PhysicsUtil.Trajectory lastTentativeTailTrajectory;
    private PhysicsUtil.Trajectory lastPredictedOpponentTrajectory;

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
        this.lastAimSolution = null;
        this.lastPlannedTrajectory = null;
        this.lastTentativeTailTrajectory = null;
        this.lastPredictedOpponentTrajectory = null;
    }

    @Override
    public BattlePlan getPlan() {
        if (info == null) {
            throw new IllegalStateException("PerfectPredictionMode must be initialized before planning");
        }

        RobotSnapshot myNow = info.captureRobotSnapshot();
        ReactiveOpponentPredictor predictor = currentPredictor();
        advanceWaypointQueue(myNow);
        pruneExpiredCommittedShotImpactTimes(myNow.time);
        rebalanceCommittedPath(myNow, requiredCommittedTicks(myNow.time));

        EnemyInfo enemy = info.getEnemy();
        PredictedOpponentState opponentStart = buildOpponentStart(enemy, myNow);
        PlanState planState = currentPlanState(myNow);
        PredictionResult prediction = null;
        PhysicsUtil.Trajectory effectiveTrajectory = planState.ourTrajectory;
        if (enemy != null && enemy.alive && enemy.seenThisRound) {
            int requiredTailTicks = MIN_TAIL_DURATION_TICKS;
            for (int attempt = 0; attempt < MAX_TAIL_EXTENSION_ATTEMPTS; attempt++) {
                tentativeTailLegs = movement.generateBestRandomTail(
                        planState.ourTrajectory,
                        myNow.time,
                        opponentStart,
                        predictor,
                        requiredTailTicks,
                        tentativeTailLegs);
                lastTentativeTailTrajectory = simulateTailTrajectory(
                        planState.ourTrajectory, tentativeTailLegs);
                effectiveTrajectory = buildEffectiveTrajectory(planState.ourTrajectory, lastTentativeTailTrajectory);
                if (effectiveTrajectory.length() >= 2) {
                    prediction = predictOpponent(myNow, effectiveTrajectory, predictor);
                }
                if (prediction != null && prediction.wavePassed) {
                    break;
                }
                int currentTailTicks = Math.max(0, effectiveTrajectory.length() - planState.ourTrajectory.length());
                requiredTailTicks = Math.max(requiredTailTicks + TAIL_EXTENSION_STEP_TICKS,
                        currentTailTicks + TAIL_EXTENSION_STEP_TICKS);
            }
        } else {
            tentativeTailLegs = Collections.emptyList();
            lastTentativeTailTrajectory = null;
        }

        OpponentDriveSimulator.AimSolution aimSolution = prediction != null ? prediction.aimSolution : null;
        double gunTurn = aimSolution != null
                ? MathUtils.normalizeAngle(aimSolution.firingAngle - myNow.gunHeading)
                : 0.0;
        double firePower = prediction != null && prediction.wavePassed && shouldFire(myNow, aimSolution)
                ? BotConfig.PerfectPrediction.FIRE_POWER
                : 0.0;
        if (firePower >= 0.1 && aimSolution != null) {
            committedShotImpactTimes.add(myNow.time + 1L + aimSolution.interceptTick);
            rebalanceCommittedPath(myNow, requiredCommittedTicks(myNow.time));
            planState = currentPlanState(myNow);
            if (enemy != null && enemy.alive && enemy.seenThisRound) {
                lastTentativeTailTrajectory = simulateTailTrajectory(planState.ourTrajectory, tentativeTailLegs);
            }
        }

        lastPlannedTrajectory = planState != null ? planState.ourTrajectory : null;
        lastAimSolution = aimSolution;
        lastPredictedOpponentTrajectory = prediction != null ? prediction.opponentTrajectory : null;

        if (planState != null && planState.movementPlan.activeWaypoint(myNow.time) != null) {
            return planState.movementPlan.createExecutionPlan(myNow, gunTurn, firePower);
        }
        if (!tentativeTailLegs.isEmpty()) {
            return createExecutionPlanForLeg(myNow, tentativeTailLegs.get(0), gunTurn, firePower);
        }
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
                                                 double gunTurn,
                                                 double firePower) {
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                myNow.x,
                myNow.y,
                myNow.heading,
                myNow.velocity,
                leg.targetX,
                leg.targetY,
                PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                leg.steeringMode,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return new BattlePlan(instruction[0], instruction[1], gunTurn, firePower);
    }

    private void pruneExpiredCommittedShotImpactTimes(long currentTime) {
        for (int i = committedShotImpactTimes.size() - 1; i >= 0; i--) {
            if (committedShotImpactTimes.get(i) <= currentTime) {
                committedShotImpactTimes.remove(i);
            }
        }
    }

    private int requiredCommittedTicks(long currentTime) {
        long latestImpactTime = currentTime;
        for (Long impactTime : committedShotImpactTimes) {
            if (impactTime != null && impactTime > latestImpactTime) {
                latestImpactTime = impactTime;
            }
        }
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, latestImpactTime - currentTime));
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
        List<ScriptedWaypoint> remainingWaypoints = currentPlanState(myNow).movementPlan.remainingWaypoints(myNow.time);
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
                                                            List<PathLeg> tailLegs) {
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
                    PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                    leg.steeringMode,
                    bfWidth,
                    bfHeight);
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

    private PlanState currentPlanState(RobotSnapshot myNow) {
        CommittedWaypointPlan movementPlan = new CommittedWaypointPlan(
                planStartTime,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight(),
                waypoints);
        return new PlanState(movementPlan, movementPlan.simulateRemainingTrajectory(myNow));
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
        final PhysicsUtil.Trajectory ourTrajectory;

        PlanState(CommittedWaypointPlan movementPlan, PhysicsUtil.Trajectory ourTrajectory) {
            this.movementPlan = movementPlan;
            this.ourTrajectory = ourTrajectory;
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
