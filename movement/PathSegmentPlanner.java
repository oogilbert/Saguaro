package oog.mega.saguaro.movement;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.PhysicsUtil;

final class PathSegmentPlanner {
    private static final double ROBOT_MAX_VELOCITY = 8.0;
    private static final double ROBOT_DECELERATION = 2.0;
    private static final int FIXED_RUNWAY_TICKS = 8;
    // Distance covered accelerating from 0 to 8 over 8 ticks: 1+2+...+8.
    private static final double FIXED_RUNWAY_DISTANCE = 36.0;

    static final class SegmentPlan {
        public final PhysicsUtil.Trajectory segment;
        public final double firstTargetX;
        public final double firstTargetY;
        public final int firstLegDurationTicks;

        SegmentPlan(PhysicsUtil.Trajectory segment,
                    double firstTargetX,
                    double firstTargetY,
                    int firstLegDurationTicks) {
            this.segment = segment;
            this.firstTargetX = firstTargetX;
            this.firstTargetY = firstTargetY;
            this.firstLegDurationTicks = firstLegDurationTicks;
        }
    }

    private static final class DirectSegmentPlan {
        public final PhysicsUtil.Trajectory segment;
        public final double targetX;
        public final double targetY;

        DirectSegmentPlan(PhysicsUtil.Trajectory segment, double targetX, double targetY) {
            this.segment = segment;
            this.targetX = targetX;
            this.targetY = targetY;
        }
    }

    private static final class RunwayPlan {
        public final int switchTick;
        public final double setupX;
        public final double setupY;
        public final double throughX;
        public final double throughY;

        RunwayPlan(int switchTick,
                   double setupX,
                   double setupY,
                   double throughX,
                   double throughY) {
            this.switchTick = switchTick;
            this.setupX = setupX;
            this.setupY = setupY;
            this.throughX = throughX;
            this.throughY = throughY;
        }
    }

    SegmentPlan buildStrategySegmentPlan(Wave wave,
                                         PhysicsUtil.PositionState state,
                                         long stateTime,
                                         int nominalFirstContactTicks,
                                         double targetAngle,
                                         double impactX,
                                         double impactY,
                                         double tangentCwX,
                                         double tangentCwY,
                                         WaveStrategy strategy,
                                         double bfWidth,
                                         double bfHeight) {
        // The nominal first-contact tick only shapes the intended target point.
        // The generated segment itself still runs until the tracked wave first
        // reaches the bot body, so later-wave handoff stays consistent.
        if (strategy.isVelocityShaping()) {
            RunwayPlan shapingPlan = buildVelocityShapingPlan(
                    state, nominalFirstContactTicks, impactX, impactY, tangentCwX, tangentCwY, strategy, bfWidth, bfHeight);
            // This segment runs until the tracked wave first reaches the bot body.
            PhysicsUtil.Trajectory segment = PhysicsUtil.simulateMultiSegmentTrajectory(
                    state, stateTime,
                    shapingPlan.setupX, shapingPlan.setupY,
                    shapingPlan.throughX, shapingPlan.throughY,
                    stateTime + shapingPlan.switchTick,
                    wave,
                    PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                    PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                    bfWidth, bfHeight);
            double firstTargetX = shapingPlan.switchTick <= 0 ? shapingPlan.throughX : shapingPlan.setupX;
            double firstTargetY = shapingPlan.switchTick <= 0 ? shapingPlan.throughY : shapingPlan.setupY;
            int firstLegDurationTicks = shapingPlan.switchTick <= 0
                    ? segment.length() - 1
                    : Math.min(shapingPlan.switchTick, segment.length() - 1);
            return new SegmentPlan(segment, firstTargetX, firstTargetY, firstLegDurationTicks);
        }

        DirectSegmentPlan directPlan = buildDirectStrategyPlan(
                wave, state, stateTime, nominalFirstContactTicks, targetAngle, strategy, bfWidth, bfHeight);
        return new SegmentPlan(
                directPlan.segment,
                directPlan.targetX,
                directPlan.targetY,
                directPlan.segment.length() - 1);
    }

    private DirectSegmentPlan buildDirectStrategyPlan(Wave wave,
                                                      PhysicsUtil.PositionState state,
                                                      long stateTime,
                                                      int nominalFirstContactTicks,
                                                      double targetAngle,
                                                      WaveStrategy strategy,
                                                      double bfWidth,
                                                      double bfHeight) {
        double currentRadius = Math.hypot(state.x - wave.originX, state.y - wave.originY);
        if (strategy == WaveStrategy.STRAIGHT) {
            return buildDirectSegmentForDesiredRadius(
                    wave, state, stateTime, targetAngle, currentRadius,
                    PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                    bfWidth, bfHeight);
        }

        double radialVelocity = radialVelocityFromWaveOrigin(wave, state);
        int radialCommandSign = strategy.radialCommandSign();
        double radialOffset = simulateDisplacement(
                radialVelocity, radialCommandSign, Math.max(1, nominalFirstContactTicks));
        double desiredRadius = currentRadius + radialOffset;
        return buildDirectSegmentForDesiredRadius(
                wave, state, stateTime, targetAngle, desiredRadius,
                PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                bfWidth, bfHeight);
    }

    private DirectSegmentPlan buildDirectSegmentForDesiredRadius(Wave wave,
                                                                 PhysicsUtil.PositionState state,
                                                                 long stateTime,
                                                                 double targetAngle,
                                                                 double desiredRadius,
                                                                 PhysicsUtil.EndpointBehavior endpointBehavior,
                                                                 double bfWidth,
                                                                 double bfHeight) {
        double minRadius = MovementEngine.CIRCULAR_WAVE_APPROX_RADIUS;
        double maxRadius =
                MovementEngine.maxDistanceInField(wave.originX, wave.originY, targetAngle, bfWidth, bfHeight);
        if (maxRadius < minRadius) {
            minRadius = maxRadius;
        }

        double targetRadius = Math.max(minRadius, Math.min(maxRadius, desiredRadius));

        double targetX = wave.originX + FastTrig.sin(targetAngle) * targetRadius;
        double targetY = wave.originY + FastTrig.cos(targetAngle) * targetRadius;
        targetX = MovementEngine.clampToField(targetX, bfWidth, true);
        targetY = MovementEngine.clampToField(targetY, bfHeight, false);

        // This segment runs until the tracked wave first reaches the bot body.
        PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                state, targetX, targetY,
                stateTime, wave, null, endpointBehavior,
                bfWidth, bfHeight);
        return new DirectSegmentPlan(segment, targetX, targetY);
    }

    private RunwayPlan buildVelocityShapingPlan(PhysicsUtil.PositionState state,
                                                int nominalFirstContactTicks,
                                                double impactX,
                                                double impactY,
                                                double tangentCwX,
                                                double tangentCwY,
                                                WaveStrategy strategy,
                                                double bfWidth,
                                                double bfHeight) {
        int totalTicks = Math.max(1, nominalFirstContactTicks);
        int desiredTangentialVelocitySign = strategy.desiredTangentialVelocitySign();
        int switchTick = totalTicks <= FIXED_RUNWAY_TICKS ? 0 : totalTicks - FIXED_RUNWAY_TICKS;
        double[] setupTarget = pointOnTangentialRay(
                impactX,
                impactY,
                tangentCwX,
                tangentCwY,
                -desiredTangentialVelocitySign,
                FIXED_RUNWAY_DISTANCE,
                bfWidth,
                bfHeight);
        setupTarget[0] = MovementEngine.clampToField(setupTarget[0], bfWidth, true);
        setupTarget[1] = MovementEngine.clampToField(setupTarget[1], bfHeight, false);
        double[] throughTarget = pointOnTangentialRay(
                impactX,
                impactY,
                tangentCwX,
                tangentCwY,
                desiredTangentialVelocitySign,
                Double.POSITIVE_INFINITY,
                bfWidth,
                bfHeight);
        return new RunwayPlan(
                switchTick,
                setupTarget[0],
                setupTarget[1],
                throughTarget[0],
                throughTarget[1]);
    }

    private static double stepTangentialVelocity(double currentVelocity, int commandSign) {
        double move = commandSign;
        if (currentVelocity == 0.0) {
            return move;
        }
        if (Math.signum(currentVelocity) == move) {
            return clampValue(currentVelocity + move, -ROBOT_MAX_VELOCITY, ROBOT_MAX_VELOCITY);
        }
        double absVelocity = Math.abs(currentVelocity);
        if (absVelocity >= ROBOT_DECELERATION) {
            return currentVelocity + ROBOT_DECELERATION * move;
        }
        double stoppingFraction = absVelocity / ROBOT_DECELERATION;
        return currentVelocity
                + ROBOT_DECELERATION * move * stoppingFraction
                + move * (1.0 - stoppingFraction);
    }

    private static double[] pointOnTangentialRay(double originX,
                                                 double originY,
                                                 double tangentCwX,
                                                 double tangentCwY,
                                                 int commandSign,
                                                 double desiredDistance,
                                                 double bfWidth,
                                                 double bfHeight) {
        double directionX = tangentCwX * commandSign;
        double directionY = tangentCwY * commandSign;
        double angle = Math.atan2(directionX, directionY);
        double maxDistance = MovementEngine.maxDistanceInField(originX, originY, angle, bfWidth, bfHeight);
        double distance = Math.max(0.0, Math.min(desiredDistance, maxDistance));
        return new double[]{
                originX + directionX * distance,
                originY + directionY * distance
        };
    }

    static double simulateDisplacement(double initialVelocity, int commandSign, int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        double velocity = clampValue(initialVelocity, -ROBOT_MAX_VELOCITY, ROBOT_MAX_VELOCITY);
        double displacement = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = stepTangentialVelocity(velocity, commandSign);
            displacement += velocity;
        }
        return displacement;
    }

    private static double radialVelocityFromWaveOrigin(Wave wave, PhysicsUtil.PositionState state) {
        double radialAngle = Math.atan2(state.x - wave.originX, state.y - wave.originY);
        double velocityX = FastTrig.sin(state.heading) * state.velocity;
        double velocityY = FastTrig.cos(state.heading) * state.velocity;
        double radialX = FastTrig.sin(radialAngle);
        double radialY = FastTrig.cos(radialAngle);
        return velocityX * radialX + velocityY * radialY;
    }

    private static double clampValue(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
