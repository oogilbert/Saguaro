package oog.mega.saguaro.info.state;

import oog.mega.saguaro.Saguaro;

public final class RobotMotionTracker {
    private Saguaro robot;
    private long motionStateTime;
    private double lastVelocity;
    private int accelerationSign;
    private int ticksSinceVelocityReversal;
    private int ticksSinceDecel;
    private int lastNonZeroVelocitySign;

    public void init(Saguaro robot) {
        if (robot == null) {
            throw new IllegalArgumentException("RobotMotionTracker requires a non-null robot");
        }
        this.robot = robot;
        motionStateTime = Long.MIN_VALUE;
        lastVelocity = 0.0;
        accelerationSign = 0;
        ticksSinceVelocityReversal = 0;
        ticksSinceDecel = 0;
        lastNonZeroVelocitySign = 0;
    }

    public void update() {
        long currentTime = robot.getTime();
        if (motionStateTime == currentTime) {
            return;
        }

        double currentVelocity = robot.getVelocity();
        if (motionStateTime == Long.MIN_VALUE) {
            accelerationSign = 0;
            ticksSinceDecel = 0;
        } else {
            accelerationSign = signWithEpsilon(currentVelocity - lastVelocity);
            if (Math.abs(currentVelocity) < Math.abs(lastVelocity) - 1e-9) {
                ticksSinceDecel = 0;
            } else {
                ticksSinceDecel++;
            }
            int currentVelocitySign = signWithEpsilon(currentVelocity);
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

        int currentVelocitySign = signWithEpsilon(currentVelocity);
        if (motionStateTime == Long.MIN_VALUE && currentVelocitySign != 0) {
            lastNonZeroVelocitySign = currentVelocitySign;
        }
        lastVelocity = currentVelocity;
        motionStateTime = currentTime;
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

    private static int signWithEpsilon(double value) {
        if (value > 1e-9) {
            return 1;
        }
        if (value < -1e-9) {
            return -1;
        }
        return 0;
    }
}
