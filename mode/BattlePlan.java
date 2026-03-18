package oog.mega.saguaro.mode;

public final class BattlePlan {
    public final double moveDistance;
    public final double turnAngle;
    public final double gunTurnAngle;
    public final double firePower;

    public BattlePlan(double moveDistance, double turnAngle, double gunTurnAngle, double firePower) {
        this.moveDistance = moveDistance;
        this.turnAngle = turnAngle;
        this.gunTurnAngle = gunTurnAngle;
        this.firePower = firePower;
    }
}
