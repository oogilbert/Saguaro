package oog.mega.saguaro.info.state;

import oog.mega.saguaro.Saguaro;

public final class RobotMotionTracker {
    private Saguaro robot;
    private boolean firstUpdate;
    private double lastVelocity;
    private int accelerationSign;
    private int ticksSinceVelocityReversal;
    private int ticksSinceDecel;
    private int lastNonZeroVelocitySign;

    public void init(Saguaro robot) {
        this.robot = robot;
        firstUpdate = true;
        lastVelocity = 0.0;
        accelerationSign = 0;
        ticksSinceVelocityReversal = 0;
        ticksSinceDecel = 0;
        lastNonZeroVelocitySign = 0;
    }

    public void update() {
        double currentVelocity = robot.getVelocity();
        int currentVelocitySign = currentVelocity > 0 ? 1 : currentVelocity < 0 ? -1 : 0;
        if (firstUpdate) {
            firstUpdate = false;
            accelerationSign = 0;
            ticksSinceDecel = 0;
            if (currentVelocitySign != 0) {
                lastNonZeroVelocitySign = currentVelocitySign;
            }
        } else {
            accelerationSign = currentVelocity > lastVelocity ? 1 : currentVelocity < lastVelocity ? -1 : 0;
            if (Math.abs(currentVelocity) < Math.abs(lastVelocity)) {
                ticksSinceDecel = 0;
            } else {
                ticksSinceDecel++;
            }
            if (currentVelocitySign != 0) {
                if (lastNonZeroVelocitySign != 0 && currentVelocitySign != lastNonZeroVelocitySign) {
                    ticksSinceVelocityReversal = 0;
                } else {
                    ticksSinceVelocityReversal++;
                }
                lastNonZeroVelocitySign = currentVelocitySign;
            } else {
                ticksSinceVelocityReversal++;
            }
        }

        lastVelocity = currentVelocity;
    }

    public int getAccelerationSign() {
        return accelerationSign;
    }

    public int getTicksSinceVelocityReversal() {
        return ticksSinceVelocityReversal;
    }

    public int getTicksSinceDecel() {
        return ticksSinceDecel;
    }

}
