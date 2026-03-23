package oog.mega.saguaro.movement;

import oog.mega.saguaro.math.PhysicsUtil;

public final class PathLeg {
    public final double targetX;
    public final double targetY;
    public final int durationTicks;
    public final PhysicsUtil.SteeringMode steeringMode;

    public PathLeg(double targetX, double targetY, int durationTicks) {
        this(targetX, targetY, durationTicks, PhysicsUtil.SteeringMode.DIRECT);
    }

    public PathLeg(double targetX,
                   double targetY,
                   int durationTicks,
                   PhysicsUtil.SteeringMode steeringMode) {
        if (!Double.isFinite(targetX) || !Double.isFinite(targetY)) {
            throw new IllegalArgumentException("PathLeg requires finite target coordinates");
        }
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("PathLeg duration must be positive");
        }
        this.targetX = targetX;
        this.targetY = targetY;
        this.durationTicks = durationTicks;
        this.steeringMode = steeringMode;
    }
}
