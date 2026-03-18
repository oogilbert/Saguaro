package oog.mega.saguaro.info.state;

public final class RobotSnapshot {
    public final double x;
    public final double y;
    public final double heading;
    public final double velocity;
    public final double gunHeading;
    public final double gunHeat;
    public final double energy;
    public final double gunCoolingRate;
    public final long time;

    public RobotSnapshot(double x,
                         double y,
                         double heading,
                         double velocity,
                         double gunHeading,
                         double gunHeat,
                         double energy,
                         double gunCoolingRate,
                         long time) {
        this.x = x;
        this.y = y;
        this.heading = heading;
        this.velocity = velocity;
        this.gunHeading = gunHeading;
        this.gunHeat = gunHeat;
        this.energy = energy;
        this.gunCoolingRate = gunCoolingRate;
        this.time = time;
    }
}
