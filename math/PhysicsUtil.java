package oog.mega.saguaro.math;

import java.util.ArrayList;
import java.util.List;
import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.Wave;
import robocode.Rules;

/**
 * Applies one tick of Robocode physics to predict the bot's next state.
 */
public final class PhysicsUtil {

    private static final double MAX_VELOCITY = 8.0;
    private static final double DECELERATION = 2.0;
    private static final double PASS_THROUGH_COMMAND_DISTANCE = 1000.0;
    private static final int MAX_SIMULATION_TICKS = 2000;
    private static final double STOPPING_DISTANCE_EPS = 1e-9;
    private static final double RIGHT_ANGLE = Math.PI * 0.5;
    public static final double WALL_MARGIN = 18.0;

    public enum EndpointBehavior {
        PASS_THROUGH,
        PARK_AND_WAIT
    }

    public enum SteeringMode {
        DIRECT(0.0),
        WALL_SMOOTHED_CW(1.0),
        WALL_SMOOTHED_CCW(-1.0);

        final double wallSmoothingTurnSign;

        SteeringMode(double wallSmoothingTurnSign) {
            this.wallSmoothingTurnSign = wallSmoothingTurnSign;
        }

        boolean usesWallSmoothing() {
            return this != DIRECT;
        }
    }

    public static class PositionState {
        public final double x;
        public final double y;
        public final double heading;
        public final double velocity;
        public final boolean hitWall;
        public final double wallHitDamage;

        public PositionState(double x, double y, double heading, double velocity) {
            this(x, y, heading, velocity, false, 0.0);
        }

        public PositionState(double x, double y, double heading, double velocity,
                             boolean hitWall, double wallHitDamage) {
            this.x = x;
            this.y = y;
            this.heading = heading;
            this.velocity = velocity;
            this.hitWall = hitWall;
            this.wallHitDamage = wallHitDamage;
        }
    }

    /**
     * Calculates the bot state after one tick of Robocode physics.
     *
     * @param x current x position
     * @param y current y position
     * @param heading current heading in radians (Robocode convention: 0 = north, clockwise)
     * @param velocity current velocity
     * @param moveDistance the setAhead() distance (positive = forward, negative = backward)
     * @param turnAngle the setTurnRightRadians() angle
     * @param bfWidth battlefield width
     * @param bfHeight battlefield height
     * @return the predicted next-tick state
     */
    public static PositionState calculateNextTick(double x, double y, double heading, double velocity,
                                                   double moveDistance, double turnAngle,
                                                   double bfWidth, double bfHeight) {
        // 1. Turn: max turn rate depends on current velocity
        double maxTurnRate = maxTurnRateRadians(velocity);
        double clampedTurn = clamp(turnAngle, -maxTurnRate, maxTurnRate);
        double newHeading = heading + clampedTurn;

        // 2. Velocity: follow Robocode's distance-remaining based movement update
        double newVelocity = nextVelocityForDistanceRemaining(velocity, moveDistance);

        // 3. Move
        double newX = x + newVelocity * FastTrig.sin(newHeading);
        double newY = y + newVelocity * FastTrig.cos(newHeading);
        return resolveWallCollision(newX, newY, newHeading, newVelocity, bfWidth, bfHeight);
    }

    public static double maxTurnRateRadians(double velocity) {
        return Math.toRadians(10.0 - 0.75 * Math.abs(velocity));
    }

    public static double minVelocityDelta(double currentVelocity) {
        return stepVelocity(currentVelocity, -1.0) - currentVelocity;
    }

    public static double maxVelocityDelta(double currentVelocity) {
        return stepVelocity(currentVelocity, 1.0) - currentVelocity;
    }

    /**
     * Steps velocity by one tick given a move instruction.
     * Adapted from StepVelocity.txt reference implementation.
     *
     * @param currentVel current velocity
     * @param move move instruction in [-1, 1]: +1 = accelerate forward, -1 = accelerate backward
     * @return new velocity after one tick
     */
    static double stepVelocity(double currentVel, double move) {
        if (move == 0) {
            double absVelocity = Math.abs(currentVel);
            if (absVelocity <= DECELERATION) {
                return 0.0;
            }
            return currentVel - Math.signum(currentVel) * DECELERATION;
        }
        // Starting from rest: pure acceleration
        if (currentVel == 0) {
            return move;
        }
        // Accelerating if same sign
        if (Math.signum(currentVel) == Math.signum(move)) {
            return clamp(currentVel + move, -MAX_VELOCITY, MAX_VELOCITY);
        }
        // Reversing: decelerating
        double absv = Math.abs(currentVel);
        // Pure reversal case (not crossing zero this tick)
        if (absv >= DECELERATION) {
            return currentVel + DECELERATION * move;
        }
        // Case where we cross zero this tick
        double stoppingFraction = absv / DECELERATION;
        return currentVel + DECELERATION * move * stoppingFraction + move * (1 - stoppingFraction);
    }

    private static double nextVelocityForDistanceRemaining(double currentVelocity, double distanceRemaining) {
        if (Double.isNaN(distanceRemaining)) {
            distanceRemaining = 0.0;
        }
        if (distanceRemaining < 0.0) {
            return -nextVelocityForDistanceRemaining(-currentVelocity, -distanceRemaining);
        }

        double goalVelocity = Double.isInfinite(distanceRemaining)
                ? MAX_VELOCITY
                : Math.min(maxReachableVelocity(distanceRemaining), MAX_VELOCITY);
        if (currentVelocity >= 0.0) {
            return Math.max(currentVelocity - DECELERATION, Math.min(goalVelocity, currentVelocity + 1.0));
        }
        return Math.max(
                currentVelocity - 1.0,
                Math.min(goalVelocity, currentVelocity + maxDecelForSpeed(-currentVelocity)));
    }

    private static double maxReachableVelocity(double distanceRemaining) {
        double decelTime = Math.max(
                1.0,
                Math.ceil((Math.sqrt(4.0 * distanceRemaining + 1.0) - 1.0) * 0.5));
        if (Double.isInfinite(decelTime)) {
            return MAX_VELOCITY;
        }
        double decelDistance = decelTime * (decelTime - 1.0);
        return (decelTime - 1.0) * DECELERATION
                + (distanceRemaining - decelDistance) / decelTime;
    }

    private static double maxDecelForSpeed(double speed) {
        double decelTime = speed * 0.5;
        double accelTime = 1.0 - decelTime;
        return Math.min(1.0, decelTime) * DECELERATION + Math.max(0.0, accelTime);
    }

    public static class Trajectory {
        public final PositionState[] states;

        public Trajectory(PositionState[] states) {
            this.states = states;
        }

        public PositionState stateAt(int tickOffset) {
            if (tickOffset < 0) return states[0];
            if (tickOffset >= states.length) return states[states.length - 1];
            return states[tickOffset];
        }

        public int length() {
            return states.length;
        }
    }

    /**
     * Computes the moveDistance and turnAngle needed to move toward (targetX, targetY).
     * Chooses forward vs backward based on which requires less turning.
     * Uses a long setAhead-style command so targets are treated as directional waypoints
     * without implicit endpoint braking.
     *
     * @return double[]{moveDistance, turnAngle}
     */
    public static double[] computeMovementInstruction(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY) {
        double[] instruction = new double[2];
        computeMovementInstructionInto(x, y, heading, velocity, targetX, targetY, true, instruction);
        return instruction;
    }

    public static double[] computeMovementInstruction(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY,
                                                      EndpointBehavior endpointBehavior) {
        double[] instruction = new double[2];
        computeMovementInstructionInto(x, y, heading, velocity, targetX, targetY, endpointBehavior, instruction);
        return instruction;
    }

    public static double[] computeMovementInstruction(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY,
                                                      EndpointBehavior endpointBehavior,
                                                      SteeringMode steeringMode,
                                                      double bfWidth,
                                                      double bfHeight) {
        double[] instruction = new double[2];
        computeMovementInstructionInto(
                x,
                y,
                heading,
                velocity,
                targetX,
                targetY,
                endpointBehavior,
                steeringMode,
                bfWidth,
                bfHeight,
                instruction);
        return instruction;
    }

    /**
     * Writes moveDistance and turnAngle toward (targetX, targetY) into {@code out}.
     * out[0] = moveDistance, out[1] = turnAngle
     */
    public static void computeMovementInstructionInto(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY, double[] out) {
        computeMovementInstructionInto(x, y, heading, velocity, targetX, targetY, true, out);
    }

    public static void computeMovementInstructionInto(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY,
                                                      EndpointBehavior endpointBehavior,
                                                      double[] out) {
        computeMovementInstructionInto(
                x,
                y,
                heading,
                velocity,
                targetX,
                targetY,
                endpointBehavior,
                SteeringMode.DIRECT,
                Double.NaN,
                Double.NaN,
                out);
    }

    public static void computeMovementInstructionInto(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY,
                                                      EndpointBehavior endpointBehavior,
                                                      SteeringMode steeringMode,
                                                      double bfWidth,
                                                      double bfHeight,
                                                      double[] out) {
        if (endpointBehavior == null) {
            throw new IllegalArgumentException("Endpoint behavior must be non-null");
        }
        PositionState state = new PositionState(x, y, heading, velocity);
        if (endpointBehavior == EndpointBehavior.PARK_AND_WAIT) {
            if (shouldStartParking(state, targetX, targetY)) {
                out[0] = 0.0;
                out[1] = 0.0;
                return;
            }
            computeMovementInstructionInto(
                    x,
                    y,
                    heading,
                    velocity,
                    targetX,
                    targetY,
                    steeringMode,
                    bfWidth,
                    bfHeight,
                    true,
                    out);
            return;
        }
        if (isAtTarget(state, targetX, targetY)) {
            computePassThroughInstructionInto(state, out);
            return;
        }
        computeMovementInstructionInto(
                x,
                y,
                heading,
                velocity,
                targetX,
                targetY,
                steeringMode,
                bfWidth,
                bfHeight,
                true,
                out);
    }

    /**
     * Writes moveDistance and turnAngle toward (targetX, targetY) into {@code out}.
     * out[0] = moveDistance, out[1] = turnAngle
     *
     * @param allowReverse whether backward movement is allowed when it reduces required turning
     */
    public static void computeMovementInstructionInto(double x, double y, double heading, double velocity,
                                                      double targetX, double targetY, boolean allowReverse,
                                                      double[] out) {
        computeMovementInstructionInto(
                x,
                y,
                heading,
                velocity,
                targetX,
                targetY,
                SteeringMode.DIRECT,
                Double.NaN,
                Double.NaN,
                allowReverse,
                out);
    }

    private static void computeMovementInstructionInto(double x, double y, double heading, double velocity,
                                                       double targetX, double targetY,
                                                       SteeringMode steeringMode,
                                                       double bfWidth,
                                                       double bfHeight,
                                                       boolean allowReverse,
                                                       double[] out) {
        if (out == null || out.length < 2) {
            throw new IllegalArgumentException("Output buffer must have length >= 2");
        }

        double desiredHeading = computeTravelAngle(x, y, targetX, targetY, steeringMode, bfWidth, bfHeight);
        if (!Double.isFinite(desiredHeading)) {
            out[0] = 0.0;
            out[1] = 0.0;
            return;
        }

        double forwardTurn = MathUtils.normalizeAngle(desiredHeading - heading);
        if (!allowReverse) {
            out[0] = PASS_THROUGH_COMMAND_DISTANCE;
            out[1] = forwardTurn;
            return;
        }

        double backwardTurn = MathUtils.normalizeAngle(desiredHeading - heading + Math.PI);
        if (Math.abs(forwardTurn) <= Math.abs(backwardTurn)) {
            out[0] = PASS_THROUGH_COMMAND_DISTANCE;
            out[1] = forwardTurn;
        } else {
            out[0] = -PASS_THROUGH_COMMAND_DISTANCE;
            out[1] = backwardTurn;
        }
    }

    public static double computeTravelAngle(double x,
                                            double y,
                                            double targetX,
                                            double targetY,
                                            SteeringMode steeringMode,
                                            double bfWidth,
                                            double bfHeight) {
        double dx = targetX - x;
        double dy = targetY - y;
        double distanceSquared = dx * dx + dy * dy;
        if (distanceSquared < 1.0) {
            return Double.NaN;
        }

        double desiredHeading = Math.atan2(dx, dy);
        if (!steeringMode.usesWallSmoothing()) {
            return desiredHeading;
        }
        return computeWallSmoothedTravelAngle(x, y, desiredHeading, steeringMode, bfWidth, bfHeight);
    }

    /**
     * Applies one movement tick toward the requested target, writing the intermediate movement
     * instruction into {@code instructionBuffer} when provided.
     */
    public static PositionState advanceTowardTarget(PositionState current,
                                                    double targetX,
                                                    double targetY,
                                                    double bfWidth,
                                                    double bfHeight,
                                                    double[] instructionBuffer) {
        return advanceTowardTarget(current, targetX, targetY, bfWidth, bfHeight, true, instructionBuffer);
    }

    /**
     * Applies one movement tick toward the requested target, optionally allowing reverse motion,
     * writing the intermediate movement instruction into {@code instructionBuffer} when provided.
     */
    public static PositionState advanceTowardTarget(PositionState current,
                                                    double targetX,
                                                    double targetY,
                                                    double bfWidth,
                                                    double bfHeight,
                                                    boolean allowReverse,
                                                    double[] instructionBuffer) {
        if (current == null) {
            throw new IllegalArgumentException("advanceTowardTarget requires a non-null position state");
        }
        if (instructionBuffer == null || instructionBuffer.length < 2) {
            throw new IllegalArgumentException("Instruction buffer must have length >= 2");
        }
        computeMovementInstructionInto(
                current.x,
                current.y,
                current.heading,
                current.velocity,
                targetX,
                targetY,
                allowReverse,
                instructionBuffer);
        return calculateNextTick(
                current.x,
                current.y,
                current.heading,
                current.velocity,
                instructionBuffer[0],
                instructionBuffer[1],
                bfWidth,
                bfHeight);
    }

    /**
     * Applies one movement tick while steering toward a desired absolute heading and velocity.
     */
    public static PositionState advanceTowardHeadingAndVelocity(PositionState current,
                                                                double targetHeading,
                                                                double targetVelocity,
                                                                double bfWidth,
                                                                double bfHeight) {
        if (current == null) {
            throw new IllegalArgumentException(
                    "advanceTowardHeadingAndVelocity requires a non-null position state");
        }
        if (!Double.isFinite(targetHeading) || !Double.isFinite(targetVelocity)) {
            throw new IllegalArgumentException(
                    "advanceTowardHeadingAndVelocity requires finite target heading and velocity");
        }
        return advanceWithExplicitDeltas(
                current,
                MathUtils.normalizeAngle(targetHeading - current.heading),
                targetVelocity - current.velocity,
                bfWidth,
                bfHeight);
    }

    public static PositionState advanceWithExplicitDeltas(PositionState current,
                                                          double turnDelta,
                                                          double velocityDelta,
                                                          double bfWidth,
                                                          double bfHeight) {
        if (current == null) {
            throw new IllegalArgumentException("advanceWithExplicitDeltas requires a non-null position state");
        }
        double clampedTurn = clamp(turnDelta, -maxTurnRateRadians(current.velocity), maxTurnRateRadians(current.velocity));
        double minVelocity = current.velocity + minVelocityDelta(current.velocity);
        double maxVelocity = current.velocity + maxVelocityDelta(current.velocity);
        double newVelocity = clamp(current.velocity + velocityDelta, minVelocity, maxVelocity);
        double newHeading = current.heading + clampedTurn;
        double newX = current.x + newVelocity * FastTrig.sin(newHeading);
        double newY = current.y + newVelocity * FastTrig.cos(newHeading);
        return resolveWallCollision(newX, newY, newHeading, newVelocity, bfWidth, bfHeight);
    }

    /**
     * Simulates a braking trajectory that issues moveDistance=0 each tick.
     * Useful for explicit stop-path branches.
     */
    public static Trajectory simulateStopTrajectory(PositionState start, int ticks,
                                                    double bfWidth, double bfHeight) {
        PositionState[] states = new PositionState[ticks + 1];
        states[0] = start;

        PositionState current = start;
        for (int i = 0; i < ticks; i++) {
            current = calculateNextTick(
                    current.x, current.y, current.heading, current.velocity,
                    0.0, 0.0, bfWidth, bfHeight);
            states[i + 1] = current;
        }

        return new Trajectory(states);
    }

    /**
     * Simulates a multi-tick trajectory toward (targetX, targetY).
     *
     * @param start initial state
     * @param targetX target x position
     * @param targetY target y position
     * @param ticks number of ticks to simulate
     * @param bfWidth battlefield width
     * @param bfHeight battlefield height
     * @return Trajectory with ticks+1 states (index 0 = start)
     */
    public static Trajectory simulateTrajectory(PositionState start, double targetX, double targetY,
                                                 int ticks, double bfWidth, double bfHeight) {
        return simulateTrajectory(start, targetX, targetY, ticks, SteeringMode.DIRECT, bfWidth, bfHeight);
    }

    public static Trajectory simulateTrajectory(PositionState start,
                                                double targetX,
                                                double targetY,
                                                int ticks,
                                                SteeringMode steeringMode,
                                                double bfWidth,
                                                double bfHeight) {
        PositionState[] states = new PositionState[ticks + 1];
        states[0] = start;

        PositionState current = start;
        double[] instruction = new double[2];
        for (int i = 0; i < ticks; i++) {
            computeMovementInstructionInto(
                    current.x,
                    current.y,
                    current.heading,
                    current.velocity,
                    targetX,
                    targetY,
                    steeringMode,
                    bfWidth,
                    bfHeight,
                    true,
                    instruction);
            current = calculateNextTick(
                    current.x, current.y, current.heading, current.velocity,
                    instruction[0], instruction[1], bfWidth, bfHeight);
            states[i + 1] = current;
        }

        return new Trajectory(states);
    }

    /**
     * Simulates movement toward a target until one of the requested stop conditions is met.
     * The simulation always includes the state where the stop condition first becomes true.
     *
     * Stop conditions:
     * - trackedWave first-contact with the bot body (when provided)
     * - absolute stopTime (when provided)
     *
     * @param start initial state
     * @param targetX target x position
     * @param targetY target y position
     * @param startTime simulation start time for wave/time checks
     * @param trackedWave optional wave to stop on first-contact with the bot body
     * @param stopTime optional absolute time to stop on
     * @param endpointBehavior behavior after endpoint is reached
     * @param bfWidth battlefield width
     * @param bfHeight battlefield height
     * @return trajectory from start through the stop-condition tick
     */
    public static Trajectory simulateTrajectory(PositionState start, double targetX, double targetY,
                                                long startTime, Wave trackedWave, Long stopTime,
                                                EndpointBehavior endpointBehavior,
                                                double bfWidth, double bfHeight) {
        return simulateTrajectory(
                start,
                targetX,
                targetY,
                startTime,
                trackedWave,
                stopTime,
                endpointBehavior,
                SteeringMode.DIRECT,
                bfWidth,
                bfHeight);
    }

    public static Trajectory simulateTrajectory(PositionState start,
                                                double targetX,
                                                double targetY,
                                                long startTime,
                                                Wave trackedWave,
                                                Long stopTime,
                                                EndpointBehavior endpointBehavior,
                                                SteeringMode steeringMode,
                                                double bfWidth,
                                                double bfHeight) {
        if (trackedWave == null && stopTime == null) {
            throw new IllegalStateException(
                    "simulateTrajectory requires at least one stop condition (wave or stopTime)");
        }
        if (endpointBehavior == null) {
            throw new IllegalStateException("simulateTrajectory requires a non-null endpoint behavior");
        }

        List<PositionState> states = new ArrayList<>();
        states.add(start);

        PositionState current = start;
        long currentTime = startTime;
        boolean endpointPhase = false;
        double[] instruction = new double[2];

        for (int tick = 0; tick < MAX_SIMULATION_TICKS; tick++) {
            if (shouldStopSimulation(current, currentTime, trackedWave, stopTime)) {
                return new Trajectory(states.toArray(new PositionState[0]));
            }

            if (endpointBehavior == EndpointBehavior.PARK_AND_WAIT) {
                if (!endpointPhase && shouldStartParking(current, targetX, targetY)) {
                    endpointPhase = true;
                }
                if (endpointPhase) {
                    instruction[0] = 0.0;
                    instruction[1] = 0.0;
                } else {
                    computeMovementInstructionInto(
                            current.x,
                            current.y,
                            current.heading,
                            current.velocity,
                            targetX,
                            targetY,
                            endpointBehavior,
                            steeringMode,
                            bfWidth,
                            bfHeight,
                            instruction);
                }
            } else {
                if (!endpointPhase && isAtTarget(current, targetX, targetY)) {
                    endpointPhase = true;
                }
                if (endpointPhase) {
                    computePassThroughInstructionInto(current, instruction);
                } else {
                    computeMovementInstructionInto(
                            current.x,
                            current.y,
                            current.heading,
                            current.velocity,
                            targetX,
                            targetY,
                            endpointBehavior,
                            steeringMode,
                            bfWidth,
                            bfHeight,
                            instruction);
                }
            }

            current = calculateNextTick(
                    current.x, current.y, current.heading, current.velocity,
                    instruction[0], instruction[1], bfWidth, bfHeight);
            states.add(current);
            currentTime++;
        }

        throw new IllegalStateException(
                "Trajectory simulation exceeded max tick budget without meeting stop condition");
    }

    /**
     * Simulates a two-phase trajectory: drives toward target1 for ticks 0..switchTick-1,
     * then toward target2 for ticks switchTick..totalTicks-1.
     *
     * @param start initial state
     * @param target1X phase 1 target x
     * @param target1Y phase 1 target y
     * @param target2X phase 2 target x
     * @param target2Y phase 2 target y
     * @param switchTick tick at which to switch from target1 to target2
     * @param totalTicks total number of ticks to simulate
     * @param bfWidth battlefield width
     * @param bfHeight battlefield height
     * @return Trajectory with totalTicks+1 states (index 0 = start)
     */
    public static Trajectory simulateMultiSegmentTrajectory(
            PositionState start,
            double target1X, double target1Y,
            double target2X, double target2Y,
            int switchTick, int totalTicks,
            double bfWidth, double bfHeight) {
        PositionState[] states = new PositionState[totalTicks + 1];
        states[0] = start;

        PositionState current = start;
        double[] instruction = new double[2];
        for (int i = 0; i < totalTicks; i++) {
            double tx = i < switchTick ? target1X : target2X;
            double ty = i < switchTick ? target1Y : target2Y;
            computeMovementInstructionInto(
                    current.x, current.y, current.heading, current.velocity, tx, ty, instruction);
            current = calculateNextTick(
                    current.x, current.y, current.heading, current.velocity,
                    instruction[0], instruction[1], bfWidth, bfHeight);
            states[i + 1] = current;
        }

        return new Trajectory(states);
    }

    /**
     * Simulates a two-leg trajectory by chaining two simulateTrajectory calls.
     *
     * Leg 1 runs until switchTime or until trackedWave first-contact, whichever comes first.
     * Leg 2 runs from the leg-1 endpoint until trackedWave first-contact.
     *
     * @param start initial state
     * @param startTime simulation start time
     * @param target1X first-leg target x
     * @param target1Y first-leg target y
     * @param target2X second-leg target x
     * @param target2Y second-leg target y
     * @param switchTime absolute time to end leg 1
     * @param trackedWave wave to stop on first-contact
     * @param firstEndpointBehavior endpoint behavior used by leg 1
     * @param finalEndpointBehavior endpoint behavior used by leg 2
     * @param bfWidth battlefield width
     * @param bfHeight battlefield height
     * @return concatenated trajectory
     */
    public static Trajectory simulateMultiSegmentTrajectory(
            PositionState start,
            long startTime,
            double target1X, double target1Y,
            double target2X, double target2Y,
            long switchTime,
            Wave trackedWave,
            EndpointBehavior firstEndpointBehavior,
            EndpointBehavior finalEndpointBehavior,
            double bfWidth, double bfHeight) {
        if (trackedWave == null) {
            throw new IllegalStateException(
                    "simulateMultiSegmentTrajectory requires a tracked wave for final-leg termination");
        }
        if (firstEndpointBehavior == null || finalEndpointBehavior == null) {
            throw new IllegalStateException(
                    "simulateMultiSegmentTrajectory requires non-null endpoint behaviors");
        }

        Trajectory firstLeg = simulateTrajectory(
                start,
                target1X,
                target1Y,
                startTime,
                trackedWave,
                switchTime,
                firstEndpointBehavior,
                bfWidth,
                bfHeight);

        int firstLegTicks = firstLeg.length() - 1;
        PositionState firstEnd = firstLeg.stateAt(firstLegTicks);
        long firstEndTime = startTime + firstLegTicks;
        if (trackedWave.hasHit(firstEnd.x, firstEnd.y, firstEndTime)) {
            return firstLeg;
        }

        Trajectory secondLeg = simulateTrajectory(
                firstEnd,
                target2X,
                target2Y,
                firstEndTime,
                trackedWave,
                null,
                finalEndpointBehavior,
                bfWidth,
                bfHeight);
        return concatenateTrajectories(firstLeg, secondLeg);
    }

    /**
     * Returns the number of ticks until a wave first reaches the bot body centered at (x, y).
     * Returns 0 if the wave has already reached the bot.
     *
     * @param wave the wave
     * @param x position x
     * @param y position y
     * @param currentTime current game time
     * @return ticks until first-contact
     */
    public static int waveArrivalTick(Wave wave, double x, double y, long currentTime) {
        double dist = RobotHitbox.minDistance(wave.originX, wave.originY, x, y);
        double currentRadius = wave.getRadius(currentTime);
        double remaining = dist - currentRadius;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.ceil(remaining / wave.speed);
    }

    public static boolean isWithinBattlefield(double x, double y, double bfWidth, double bfHeight) {
        return x >= WALL_MARGIN && x <= bfWidth - WALL_MARGIN && y >= WALL_MARGIN && y <= bfHeight - WALL_MARGIN;
    }

    public static double requiredBulletPowerForDamage(double damage) {
        double clampedDamage = Math.max(0.0, damage);
        double requiredPower = clampedDamage <= 4.0
                ? clampedDamage / 4.0
                : (clampedDamage + 2.0) / 6.0;
        return Math.max(Rules.MIN_BULLET_POWER, Math.min(Rules.MAX_BULLET_POWER, requiredPower));
    }

    private static double computeWallSmoothedTravelAngle(double x,
                                                         double y,
                                                         double desiredHeading,
                                                         SteeringMode steeringMode,
                                                         double bfWidth,
                                                         double bfHeight) {
        double wallStick = BotConfig.Movement.PATH_WALL_SMOOTHING_STICK_LENGTH;
        double angleStep = BotConfig.Movement.PATH_WALL_SMOOTHING_ANGLE_STEP_RADIANS;
        double smoothedHeading = desiredHeading;
        int maxIterations = Math.max(1, (int) Math.ceil((2.0 * Math.PI) / angleStep));
        for (int i = 0; i <= maxIterations; i++) {
            double probeX = x + FastTrig.sin(smoothedHeading) * wallStick;
            double probeY = y + FastTrig.cos(smoothedHeading) * wallStick;
            if (isWithinBattlefield(probeX, probeY, bfWidth, bfHeight)) {
                return MathUtils.normalizeAngle(smoothedHeading);
            }
            smoothedHeading += steeringMode.wallSmoothingTurnSign * angleStep;
        }
        return MathUtils.normalizeAngle(smoothedHeading);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static PositionState resolveWallCollision(double x,
                                                      double y,
                                                      double heading,
                                                      double velocity,
                                                      double bfWidth,
                                                      double bfHeight) {
        double minX = WALL_MARGIN;
        double minY = WALL_MARGIN;
        double maxX = bfWidth - WALL_MARGIN;
        double maxY = bfHeight - WALL_MARGIN;

        boolean hitWall = false;
        double slideX = 0.0;
        double slideY = 0.0;

        if (x < minX) {
            hitWall = true;
            slideX = minX - x;
        } else if (x > maxX) {
            hitWall = true;
            slideX = maxX - x;
        }

        if (y < minY) {
            hitWall = true;
            slideY = minY - y;
        } else if (y > maxY) {
            hitWall = true;
            slideY = maxY - y;
        }

        if (!hitWall) {
            return new PositionState(x, y, heading, velocity, false, 0.0);
        }

        if (heading % RIGHT_ANGLE != 0.0) {
            double tangent = Math.tan(heading);
            if (slideX == 0.0) {
                slideX = slideY * tangent;
            } else if (slideY == 0.0) {
                slideY = slideX / tangent;
            } else if (Math.abs(slideX / tangent) > Math.abs(slideY)) {
                slideY = slideX / tangent;
            } else if (Math.abs(slideY * tangent) > Math.abs(slideX)) {
                slideX = slideY * tangent;
            }
        }

        double resolvedX = clamp(x + slideX, minX, maxX);
        double resolvedY = clamp(y + slideY, minY, maxY);
        double wallHitDamage = Rules.getWallHitDamage(velocity);
        return new PositionState(resolvedX, resolvedY, heading, 0.0, true, wallHitDamage);
    }

    private static boolean shouldStopSimulation(PositionState state, long time, Wave trackedWave, Long stopTime) {
        if (trackedWave != null && trackedWave.hasHit(state.x, state.y, time)) {
            return true;
        }
        return stopTime != null && time >= stopTime;
    }

    private static boolean isAtTarget(PositionState state, double targetX, double targetY) {
        double dx = targetX - state.x;
        double dy = targetY - state.y;
        return dx * dx + dy * dy < 1.0;
    }

    private static boolean shouldStartParking(PositionState state, double targetX, double targetY) {
        if (isAtTarget(state, targetX, targetY)) {
            return true;
        }
        double distance = Math.hypot(targetX - state.x, targetY - state.y);
        return distance <= estimateStoppingDistance(state.velocity) + STOPPING_DISTANCE_EPS;
    }

    private static double estimateStoppingDistance(double velocity) {
        double testVelocity = velocity;
        double distance = 0.0;
        for (int i = 0; i < 64 && Math.abs(testVelocity) > STOPPING_DISTANCE_EPS; i++) {
            testVelocity = stepVelocity(testVelocity, 0.0);
            distance += Math.abs(testVelocity);
        }
        return distance;
    }

    private static void computePassThroughInstructionInto(PositionState state, double[] out) {
        out[1] = 0.0;
        double moveSign = state.velocity < -STOPPING_DISTANCE_EPS ? -1.0 : 1.0;
        out[0] = moveSign * PASS_THROUGH_COMMAND_DISTANCE;
    }

    private static Trajectory concatenateTrajectories(Trajectory first, Trajectory second) {
        if (second.length() <= 1) {
            return first;
        }
        PositionState[] merged = new PositionState[first.length() + second.length() - 1];
        System.arraycopy(first.states, 0, merged, 0, first.length());
        System.arraycopy(second.states, 1, merged, first.length(), second.length() - 1);
        return new Trajectory(merged);
    }
}



