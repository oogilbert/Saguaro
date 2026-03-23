package oog.mega.saguaro.movement;

final class TangentialDisplacement {
    private static final double ROBOT_MAX_VELOCITY = 8.0;
    private static final double ROBOT_DECELERATION = 2.0;

    private TangentialDisplacement() {
    }

    static double simulate(double initialVelocity, int commandSign, int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        double velocity = clamp(initialVelocity, -ROBOT_MAX_VELOCITY, ROBOT_MAX_VELOCITY);
        double displacement = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = stepVelocity(velocity, commandSign);
            displacement += velocity;
        }
        return displacement;
    }

    private static double stepVelocity(double currentVelocity, int commandSign) {
        double move = commandSign;
        if (currentVelocity == 0.0) {
            return move;
        }
        if (Math.signum(currentVelocity) == move) {
            return clamp(currentVelocity + move, -ROBOT_MAX_VELOCITY, ROBOT_MAX_VELOCITY);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
