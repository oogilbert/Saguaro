package oog.mega.saguaro.movement;

public final class PathLeg {
    public final double targetX;
    public final double targetY;
    public final int durationTicks;

    public PathLeg(double targetX, double targetY, int durationTicks) {
        if (!Double.isFinite(targetX) || !Double.isFinite(targetY)) {
            throw new IllegalArgumentException("PathLeg requires finite target coordinates");
        }
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("PathLeg duration must be positive");
        }
        this.targetX = targetX;
        this.targetY = targetY;
        this.durationTicks = durationTicks;
    }
}
