package oog.mega.saguaro.mode.scripted;

public final class ScriptedWaypoint {
    public final double x;
    public final double y;
    public final int durationTicks;

    public ScriptedWaypoint(double x, double y, int durationTicks) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Scripted waypoint requires finite coordinates");
        }
        if (durationTicks < 0) {
            throw new IllegalArgumentException("Scripted waypoint duration must be non-negative");
        }
        this.x = x;
        this.y = y;
        this.durationTicks = durationTicks;
    }
}
