package oog.mega.saguaro.mode.perfectprediction;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import robocode.Rules;
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
import oog.mega.saguaro.movement.PredictedOpponentState;
import oog.mega.saguaro.movement.SurfSegmentRecommendation;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.mode.ModeController;
import oog.mega.saguaro.mode.scripted.CommittedWaypointPlan;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;
import oog.mega.saguaro.mode.scripted.ScriptedWaypoint;

/**
 * This mode is designed to take advantage of any bot whose movement is purely a function of the current state of the battle, and for whom we can
 * identify that function. This mode's movement is similar to ScoreMaxMode, except instead of changing plans every tick we commit to a future path
 * and do not deviate once it is selected. This allows us to perfectly simulate our opponent's movement and get near-100% targeting accuracy.
 * 
 * Currently only enabled for mirror bots; originally this was planned to be used against rambots but it turns out that being able to re-evaluate our
 * movement at close ranges is more important than achieving 100% accuracy
 */

public final class PerfectPredictionMode implements BattleMode {
    private static final int MAX_SEGMENT_ADVANCES_PER_TICK = 64;
    private static final int MAX_TRAVEL_ESTIMATE_TICKS = 2000;
    private static final int CLEARANCE_TIME_WINDOW = 4;
    private static final int MAX_WALL_ADJUSTMENT_STEPS = 90;
    private static final double WALL_ADJUSTMENT_STEP = Math.toRadians(2.0);
    private static final double MIN_SEGMENT_DISTANCE = 50.0;
    private static final double TARGET_POINT_MARGIN = PhysicsUtil.WALL_MARGIN + 12.0;
    private static final double MAX_TRAVEL_DISTANCE_FRACTION = 0.7;
    private static final double MIN_RANDOM_CLEARANCE = 300.0;
    private static final double FIRE_POWER = 3.0;
    private static final Color OUR_PATH_COLOR = new Color(40, 200, 255);
    private static final Color OPPONENT_PATH_COLOR = new Color(255, 80, 80);
    private static final float PATH_STROKE_WIDTH = 2.0f;

    private final RoundOutcomeProfile roundOutcomeProfile = NoOpLearningProfile.INSTANCE;
    private final Random random = new Random();
    private final List<ScriptedWaypoint> waypoints = new ArrayList<ScriptedWaypoint>();
    private final List<ModeController.DebugLine> debugLines = new ArrayList<ModeController.DebugLine>();

    private Info info;
    private MovementController movement;
    private long planStartTime;
    private OpponentDriveSimulator.AimSolution lastAimSolution;
    private PhysicsUtil.Trajectory lastPlannedTrajectory;
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
        this.debugLines.clear();
        this.lastAimSolution = null;
        this.lastPlannedTrajectory = null;
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

        PlanState planState = null;
        PredictionResult prediction = null;
        EnemyInfo enemy = info.getEnemy();
        while (true) {
            planState = currentPlanState(myNow);
            if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
                break;
            }
            if (planState.ourTrajectory.length() < 2) {
                appendNextWaypointOrThrow(myNow, predictor, "next-tick firing state");
                continue;
            }

            prediction = predictOpponent(myNow, planState.ourTrajectory, predictor);
            if (prediction == null || prediction.wavePassed) {
                break;
            }
            appendNextWaypointOrThrow(myNow, predictor, "precise-prediction aiming horizon");
        }

        lastPlannedTrajectory = planState != null ? planState.ourTrajectory : null;
        OpponentDriveSimulator.AimSolution aimSolution = prediction != null ? prediction.aimSolution : null;
        lastAimSolution = aimSolution;
        lastPredictedOpponentTrajectory = prediction != null ? prediction.opponentTrajectory : null;

        double gunTurn = aimSolution != null
                ? MathUtils.normalizeAngle(aimSolution.firingAngle - myNow.gunHeading)
                : 0.0;
        double firePower = shouldFire(myNow, aimSolution) ? FIRE_POWER : 0.0;

        rebuildDebugLines();
        return planState != null
                ? planState.movementPlan.createExecutionPlan(myNow, gunTurn, firePower)
                : new BattlePlan(0.0, 0.0, gunTurn, firePower);
    }

    @Override
    public ModeController.RenderState getRenderState() {
        return new ModeController.RenderState(
                null,
                null,
                null,
                new ArrayList<ModeController.DebugLine>(debugLines),
                false);
    }

    @Override
    public void applyColors(Saguaro robot) {
        robot.setBodyColor(new Color(32, 148, 92));
        robot.setGunColor(new Color(22, 68, 42));
        robot.setRadarColor(new Color(244, 226, 92));
        robot.setBulletColor(new Color(255, 236, 108));
        robot.setScanColor(new Color(112, 244, 182));
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
                Wave.bulletSpeed(FIRE_POWER),
                opponentTrajectory);
        if (!wavePassed) {
            return new PredictionResult(null, null, false);
        }
        OpponentDriveSimulator.AimSolution aimSolution = OpponentDriveSimulator.solveInterceptFromTrajectory(
                shooterState.x,
                shooterState.y,
                Wave.bulletSpeed(FIRE_POWER),
                opponentTrajectory);
        PhysicsUtil.Trajectory visibleTrajectory = aimSolution != null
                ? truncateTrajectory(opponentTrajectory, aimSolution.interceptTick)
                : opponentTrajectory;
        return new PredictionResult(aimSolution, visibleTrajectory, true);
    }

    private boolean shouldFire(RobotSnapshot myNow, OpponentDriveSimulator.AimSolution aimSolution) {
        return aimSolution != null
                && myNow.energy >= FIRE_POWER
                && myNow.gunHeat == 0.0;
    }

    private ScriptedWaypoint buildNextWaypoint(RobotSnapshot myNow, ReactiveOpponentPredictor predictor) {
        ScriptedWaypoint surfWaypoint = buildSurfWaypoint(myNow, predictor);
        if (surfWaypoint != null) {
            return surfWaypoint;
        }

        // A synthesized future-wave query can legitimately fail when the next wave is already
        // overlapping the bot body, so keep the legacy geometric leg as a fallback instead of
        // stalling the committed plan entirely.
        return buildLegacyWaypoint(myNow, predictor);
    }

    private void appendNextWaypointOrThrow(RobotSnapshot myNow,
                                           ReactiveOpponentPredictor predictor,
                                           String reason) {
        ScriptedWaypoint nextWaypoint = buildNextWaypoint(myNow, predictor);
        if (nextWaypoint == null) {
            throw new IllegalStateException(
                    "PerfectPrediction could not extend the committed path for " + reason);
        }
        if (nextWaypoint.durationTicks <= 0) {
            throw new IllegalStateException(
                    "PerfectPrediction generated a non-positive waypoint duration for " + reason);
        }
        waypoints.add(nextWaypoint);
    }

    private ScriptedWaypoint buildSurfWaypoint(RobotSnapshot myNow, ReactiveOpponentPredictor predictor) {
        CommittedWaypointPlan currentPlan = new CommittedWaypointPlan(
                planStartTime,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight(),
                waypoints);
        PhysicsUtil.Trajectory committedTrajectory = currentPlan.simulateRemainingTrajectory(myNow);

        EnemyInfo enemy = info.getEnemy();
        PredictedOpponentState opponentStart = null;
        if (enemy != null && enemy.alive && enemy.seenThisRound) {
            EnemyInfo.PredictedPosition enemyAtNow = enemy.predictPositionAtTime(
                    myNow.time,
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight());
            opponentStart = new PredictedOpponentState(
                    enemyAtNow.x,
                    enemyAtNow.y,
                    enemyAtNow.heading,
                    enemyAtNow.velocity,
                    enemy.gunHeat,
                    enemy.energy,
                    enemy.lastDetectedBulletPower);
        }

        SurfSegmentRecommendation recommendation = movement.recommendFutureSurfSegment(
                committedTrajectory,
                myNow.time,
                opponentStart,
                predictor);
        if (recommendation == null || recommendation.durationTicks <= 0) {
            return null;
        }
        return new ScriptedWaypoint(
                recommendation.targetX,
                recommendation.targetY,
                recommendation.durationTicks);
    }

    private ScriptedWaypoint buildLegacyWaypoint(RobotSnapshot myNow, ReactiveOpponentPredictor predictor) {
        WaypointSeed seed = buildWaypointSeed(myNow, predictor);
        if (seed == null) {
            return null;
        }

        double distanceToOpponent = Point2D.distance(
                seed.ourState.x,
                seed.ourState.y,
                seed.opponentState.x,
                seed.opponentState.y);
        if (!(distanceToOpponent > 0.0)) {
            return null;
        }

        double maxTravelDistance = distanceToOpponent * MAX_TRAVEL_DISTANCE_FRACTION;
        double travelDistance = maxTravelDistance <= MIN_SEGMENT_DISTANCE
                ? maxTravelDistance
                : MIN_SEGMENT_DISTANCE + random.nextDouble() * (maxTravelDistance - MIN_SEGMENT_DISTANCE);
        double bearingToOpponent = absoluteBearing(
                seed.ourState.x,
                seed.ourState.y,
                seed.opponentState.x,
                seed.opponentState.y);
        CandidateLeg positiveCandidate = buildCandidateLeg(
                seed,
                predictor,
                bearingToOpponent,
                travelDistance,
                Math.PI * 0.5);
        CandidateLeg negativeCandidate = buildCandidateLeg(
                seed,
                predictor,
                bearingToOpponent,
                travelDistance,
                -Math.PI * 0.5);
        return chooseCandidate(positiveCandidate, negativeCandidate).waypoint;
    }

    private WaypointSeed buildWaypointSeed(RobotSnapshot myNow, ReactiveOpponentPredictor predictor) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }

        CommittedWaypointPlan currentPlan = new CommittedWaypointPlan(
                planStartTime,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight(),
                waypoints);
        PhysicsUtil.Trajectory ourTrajectory = currentPlan.simulateRemainingTrajectory(myNow);
        EnemyInfo.PredictedPosition enemyAtNow = enemy.predictPositionAtTime(
                myNow.time,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        PhysicsUtil.PositionState enemyStart = new PhysicsUtil.PositionState(
                enemyAtNow.x,
                enemyAtNow.y,
                enemyAtNow.heading,
                enemyAtNow.velocity);
        PhysicsUtil.Trajectory opponentTrajectory = OpponentDriveSimulator.simulateTrajectory(
                enemyStart,
                ourTrajectory,
                predictor,
                ourTrajectory.length() - 1,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return new WaypointSeed(
                ourTrajectory.stateAt(ourTrajectory.length() - 1),
                opponentTrajectory.stateAt(opponentTrajectory.length() - 1));
    }

    private void rebuildDebugLines() {
        debugLines.clear();
        appendTrajectoryDebugLines(lastPlannedTrajectory, OUR_PATH_COLOR);
        appendTrajectoryDebugLines(lastPredictedOpponentTrajectory, OPPONENT_PATH_COLOR);
    }

    private void appendTrajectoryDebugLines(PhysicsUtil.Trajectory trajectory, Color color) {
        if (trajectory == null || trajectory.length() < 2) {
            return;
        }
        for (int i = 1; i < trajectory.length(); i++) {
            PhysicsUtil.PositionState previous = trajectory.states[i - 1];
            PhysicsUtil.PositionState current = trajectory.states[i];
            debugLines.add(new ModeController.DebugLine(
                    previous.x,
                    previous.y,
                    current.x,
                    current.y,
                    color,
                    PATH_STROKE_WIDTH));
        }
    }

    private CandidateLeg buildCandidateLeg(WaypointSeed seed,
                                           ReactiveOpponentPredictor predictor,
                                           double bearingToOpponent,
                                           double travelDistance,
                                           double perpendicularOffset) {
        double travelAngle = chooseInBoundsPerpendicularAngle(
                seed.ourState.x,
                seed.ourState.y,
                bearingToOpponent,
                perpendicularOffset,
                travelDistance);
        Point2D.Double point = project(seed.ourState.x, seed.ourState.y, travelAngle, travelDistance);
        int durationTicks = estimateTravelTicks(seed.ourState, point.x, point.y);
        return buildTimedCandidateLeg(seed, predictor, point.x, point.y, durationTicks);
    }

    private CandidateLeg buildTimedCandidateLeg(WaypointSeed seed,
                                                ReactiveOpponentPredictor predictor,
                                                double targetX,
                                                double targetY,
                                                int durationTicks) {
        ScriptedWaypoint waypoint = new ScriptedWaypoint(targetX, targetY, durationTicks);
        PhysicsUtil.Trajectory ourLegTrajectory = PhysicsUtil.simulateTrajectory(
                seed.ourState,
                targetX,
                targetY,
                durationTicks,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        PhysicsUtil.Trajectory opponentLegTrajectory = OpponentDriveSimulator.simulateTrajectory(
                seed.opponentState,
                ourLegTrajectory,
                predictor,
                durationTicks,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return new CandidateLeg(
                waypoint,
                minWindowedSeparation(ourLegTrajectory, opponentLegTrajectory),
                minSameTimeSeparation(ourLegTrajectory, opponentLegTrajectory));
    }

    private CandidateLeg chooseCandidate(CandidateLeg first, CandidateLeg second) {
        boolean firstClear = first.windowedSeparation >= MIN_RANDOM_CLEARANCE;
        boolean secondClear = second.windowedSeparation >= MIN_RANDOM_CLEARANCE;
        if (firstClear && secondClear) {
            return random.nextBoolean() ? first : second;
        }
        if (firstClear) {
            return first;
        }
        if (secondClear) {
            return second;
        }
        return chooseSaferCandidate(first, second);
    }

    private static CandidateLeg chooseSaferCandidate(CandidateLeg first, CandidateLeg second) {
        if (second.windowedSeparation > first.windowedSeparation + 1e-9) {
            return second;
        }
        if (first.windowedSeparation > second.windowedSeparation + 1e-9) {
            return first;
        }
        if (second.sameTimeSeparation > first.sameTimeSeparation + 1e-9) {
            return second;
        }
        return first;
    }

    private static double minWindowedSeparation(PhysicsUtil.Trajectory ourTrajectory,
                                                PhysicsUtil.Trajectory opponentTrajectory) {
        int ourTicks = ourTrajectory.length();
        int opponentTicks = opponentTrajectory.length();
        double minSeparation = Double.POSITIVE_INFINITY;
        for (int ourTick = 0; ourTick < ourTicks; ourTick++) {
            PhysicsUtil.PositionState ourState = ourTrajectory.stateAt(ourTick);
            int startTick = Math.max(0, ourTick - CLEARANCE_TIME_WINDOW);
            int endTick = Math.min(opponentTicks - 1, ourTick + CLEARANCE_TIME_WINDOW);
            for (int opponentTick = startTick; opponentTick <= endTick; opponentTick++) {
                PhysicsUtil.PositionState opponentState = opponentTrajectory.stateAt(opponentTick);
                minSeparation = Math.min(
                        minSeparation,
                        Point2D.distance(ourState.x, ourState.y, opponentState.x, opponentState.y));
            }
        }
        return minSeparation;
    }

    private static double minSameTimeSeparation(PhysicsUtil.Trajectory ourTrajectory,
                                                PhysicsUtil.Trajectory opponentTrajectory) {
        int ticks = Math.min(ourTrajectory.length(), opponentTrajectory.length());
        double minSeparation = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < ticks; tick++) {
            PhysicsUtil.PositionState ourState = ourTrajectory.stateAt(tick);
            PhysicsUtil.PositionState opponentState = opponentTrajectory.stateAt(tick);
            minSeparation = Math.min(
                    minSeparation,
                    Point2D.distance(ourState.x, ourState.y, opponentState.x, opponentState.y));
        }
        return minSeparation;
    }

    private double chooseInBoundsPerpendicularAngle(double ourX,
                                                    double ourY,
                                                    double bearingToOpponent,
                                                    double perpendicularOffset,
                                                    double travelDistance) {
        double offset = perpendicularOffset;
        for (int i = 0; i < MAX_WALL_ADJUSTMENT_STEPS; i++) {
            double candidateAngle = bearingToOpponent + offset;
            if (isProjectedPointInBounds(ourX, ourY, candidateAngle, travelDistance)) {
                return candidateAngle;
            }
            if (Math.abs(offset) <= WALL_ADJUSTMENT_STEP) {
                break;
            }
            offset -= Math.copySign(WALL_ADJUSTMENT_STEP, offset);
        }
        return bearingToOpponent;
    }

    private boolean isProjectedPointInBounds(double sourceX,
                                             double sourceY,
                                             double angle,
                                             double distance) {
        Point2D.Double point = project(sourceX, sourceY, angle, distance);
        return point.x >= TARGET_POINT_MARGIN
                && point.x <= info.getBattlefieldWidth() - TARGET_POINT_MARGIN
                && point.y >= TARGET_POINT_MARGIN
                && point.y <= info.getBattlefieldHeight() - TARGET_POINT_MARGIN;
    }

    private static double absoluteBearing(double sourceX, double sourceY, double targetX, double targetY) {
        return Math.atan2(targetX - sourceX, targetY - sourceY);
    }

    private static Point2D.Double project(double x, double y, double angle, double distance) {
        return new Point2D.Double(
                x + FastTrig.sin(angle) * distance,
                y + FastTrig.cos(angle) * distance);
    }

    private int estimateTravelTicks(PhysicsUtil.PositionState startState, double targetX, double targetY) {
        if (startState == null) {
            throw new IllegalArgumentException("Travel-time estimation requires a non-null start state");
        }

        PhysicsUtil.PositionState current = startState;
        double[] instruction = new double[2];
        for (int tick = 0; tick < MAX_TRAVEL_ESTIMATE_TICKS; tick++) {
            double dx = targetX - current.x;
            double dy = targetY - current.y;
            if (dx * dx + dy * dy < 1.0) {
                return tick;
            }
            current = PhysicsUtil.advanceTowardTarget(
                    current,
                    targetX,
                    targetY,
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight(),
                    instruction);
        }

        throw new IllegalStateException(
                "Travel-time estimate exceeded max tick budget for target (" + targetX + ", " + targetY + ")");
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

    private static final class WaypointSeed {
        final PhysicsUtil.PositionState ourState;
        final PhysicsUtil.PositionState opponentState;

        WaypointSeed(PhysicsUtil.PositionState ourState,
                     PhysicsUtil.PositionState opponentState) {
            this.ourState = ourState;
            this.opponentState = opponentState;
        }
    }

    private static final class CandidateLeg {
        final ScriptedWaypoint waypoint;
        final double windowedSeparation;
        final double sameTimeSeparation;

        CandidateLeg(ScriptedWaypoint waypoint,
                     double windowedSeparation,
                     double sameTimeSeparation) {
            this.waypoint = waypoint;
            this.windowedSeparation = windowedSeparation;
            this.sameTimeSeparation = sameTimeSeparation;
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


