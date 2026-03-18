package oog.mega.saguaro.math;

import java.util.ArrayList;
import java.util.List;

import robocode.Rules;

public final class ConstantDeltaPredictor {
    private static final int MAX_SIMULATION_TICKS = 2000;

    private ConstantDeltaPredictor() {
    }

    public static double estimateHeadingDelta(double currentHeading, double previousHeading) {
        if (!Double.isFinite(currentHeading) || !Double.isFinite(previousHeading)) {
            return 0.0;
        }
        return MathUtils.normalizeAngle(currentHeading - previousHeading);
    }

    public static double estimateVelocityDelta(double currentVelocity, double previousVelocity) {
        if (!Double.isFinite(currentVelocity) || !Double.isFinite(previousVelocity)) {
            return 0.0;
        }
        return currentVelocity - previousVelocity;
    }

    public static PhysicsUtil.PositionState advance(PhysicsUtil.PositionState current,
                                                    double headingDelta,
                                                    double velocityDelta,
                                                    double battlefieldWidth,
                                                    double battlefieldHeight) {
        return PhysicsUtil.advanceWithExplicitDeltas(
                current,
                headingDelta,
                velocityDelta,
                battlefieldWidth,
                battlefieldHeight);
    }

    public static PhysicsUtil.PositionState predictState(PhysicsUtil.PositionState start,
                                                         double headingDelta,
                                                         double velocityDelta,
                                                         int ticks,
                                                         double battlefieldWidth,
                                                         double battlefieldHeight) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Prediction tick count must be non-negative");
        }
        PhysicsUtil.PositionState current = start;
        for (int tick = 0; tick < ticks; tick++) {
            current = advance(current, headingDelta, velocityDelta, battlefieldWidth, battlefieldHeight);
        }
        return current;
    }

    public static PhysicsUtil.Trajectory simulateTrajectory(PhysicsUtil.PositionState start,
                                                            double headingDelta,
                                                            double velocityDelta,
                                                            int ticks,
                                                            double battlefieldWidth,
                                                            double battlefieldHeight) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Prediction tick count must be non-negative");
        }
        PhysicsUtil.PositionState[] states = new PhysicsUtil.PositionState[ticks + 1];
        states[0] = start;
        PhysicsUtil.PositionState current = start;
        for (int tick = 0; tick < ticks; tick++) {
            current = advance(current, headingDelta, velocityDelta, battlefieldWidth, battlefieldHeight);
            states[tick + 1] = current;
        }
        return new PhysicsUtil.Trajectory(states);
    }

    public static PhysicsUtil.Trajectory simulateUntilWavePassed(double shooterX,
                                                                 double shooterY,
                                                                 double bulletSpeed,
                                                                 PhysicsUtil.PositionState start,
                                                                 double headingDelta,
                                                                 double velocityDelta,
                                                                 double battlefieldWidth,
                                                                 double battlefieldHeight) {
        if (!Double.isFinite(shooterX) || !Double.isFinite(shooterY)) {
            throw new IllegalArgumentException("Wave-pass simulation requires finite shooter coordinates");
        }
        if (!Double.isFinite(bulletSpeed) || bulletSpeed <= Rules.MAX_VELOCITY) {
            throw new IllegalArgumentException(
                    "Wave-pass simulation requires bullet speed above max robot speed");
        }

        List<PhysicsUtil.PositionState> states = new ArrayList<PhysicsUtil.PositionState>();
        states.add(start);
        PhysicsUtil.PositionState current = start;
        for (int tick = 1; tick <= MAX_SIMULATION_TICKS; tick++) {
            current = advance(current, headingDelta, velocityDelta, battlefieldWidth, battlefieldHeight);
            states.add(current);
            double innerRadius = bulletSpeed * (tick - 1);
            if (innerRadius > RobotHitbox.maxDistance(shooterX, shooterY, current.x, current.y)) {
                return new PhysicsUtil.Trajectory(states.toArray(new PhysicsUtil.PositionState[0]));
            }
        }
        throw new IllegalStateException("Wave-pass simulation exceeded max tick budget");
    }
}
