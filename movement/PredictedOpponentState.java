package oog.mega.saguaro.movement;

import oog.mega.saguaro.math.PhysicsUtil;

public final class PredictedOpponentState {
    public final double x;
    public final double y;
    public final double heading;
    public final double velocity;
    public final double gunHeat;
    public final double energy;
    public final double lastDetectedBulletPower;

    public PredictedOpponentState(double x,
                                  double y,
                                  double heading,
                                  double velocity,
                                  double gunHeat,
                                  double energy,
                                  double lastDetectedBulletPower) {
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(heading)
                || !Double.isFinite(velocity)
                || !Double.isFinite(gunHeat)
                || !Double.isFinite(energy)
                || !Double.isFinite(lastDetectedBulletPower)) {
            throw new IllegalArgumentException("Predicted opponent state requires finite values");
        }
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.velocity = velocity;
        this.gunHeat = gunHeat;
        this.energy = energy;
        this.lastDetectedBulletPower = lastDetectedBulletPower;
    }

    public PhysicsUtil.PositionState toPositionState() {
        return new PhysicsUtil.PositionState(x, y, heading, velocity);
    }

    public boolean isAlive() {
        return energy > 0.0;
    }
}
