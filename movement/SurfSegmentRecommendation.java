package oog.mega.saguaro.movement;

public final class SurfSegmentRecommendation {
    public final double targetX;
    public final double targetY;
    public final int durationTicks;
    public final double totalDanger;

    public SurfSegmentRecommendation(double targetX,
                                     double targetY,
                                     int durationTicks,
                                     double totalDanger) {
        if (!Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(totalDanger)) {
            throw new IllegalArgumentException("Surf segment recommendation requires finite values");
        }
        if (durationTicks < 0) {
            throw new IllegalArgumentException("Surf segment recommendation duration must be non-negative");
        }
        this.targetX = targetX;
        this.targetY = targetY;
        this.durationTicks = durationTicks;
        this.totalDanger = totalDanger;
    }
}
