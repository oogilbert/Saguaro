package oog.mega.saguaro.info.state;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.math.MathUtils;

public final class RobotMotionTracker {
    private Saguaro robot;
    private final RecentMotionHistory motionHistory = new RecentMotionHistory();
    private boolean firstUpdate;
    private double lastHeading;
    private double lastVelocity;
    private double headingDelta;
    private double velocityDelta;
    private int accelerationSign;
    private int ticksSinceVelocityReversal;
    private int ticksSinceDecel;
    private int lastNonZeroVelocitySign;

    public void init(Saguaro robot) {
        this.robot = robot;
        firstUpdate = true;
        lastHeading = robot.getHeadingRadians();
        lastVelocity = 0.0;
        headingDelta = 0.0;
        velocityDelta = 0.0;
        accelerationSign = 0;
        ticksSinceVelocityReversal = 0;
        ticksSinceDecel = 0;
        lastNonZeroVelocitySign = 0;
        motionHistory.clear();
    }

    public void update() {
        double currentHeading = robot.getHeadingRadians();
        double currentVelocity = robot.getVelocity();
        int currentVelocitySign = currentVelocity > 0 ? 1 : currentVelocity < 0 ? -1 : 0;
        if (firstUpdate) {
            firstUpdate = false;
            headingDelta = 0.0;
            velocityDelta = 0.0;
            accelerationSign = 0;
            ticksSinceDecel = 0;
            if (currentVelocitySign != 0) {
                lastNonZeroVelocitySign = currentVelocitySign;
            }
        } else {
            headingDelta = MathUtils.normalizeAngle(currentHeading - lastHeading);
            velocityDelta = currentVelocity - lastVelocity;
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

        lastHeading = currentHeading;
        lastVelocity = currentVelocity;
        motionHistory.addSample(currentVelocity, headingDelta);
    }

    public double getHeadingDelta() {
        return headingDelta;
    }

    public double getVelocityDelta() {
        return velocityDelta;
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

    public int getLastNonZeroVelocitySign() {
        return lastNonZeroVelocitySign;
    }

    public int getMotionHistorySize() {
        return motionHistory.size();
    }

    public double getMotionHistoryVelocity(int index) {
        return motionHistory.velocityAt(index);
    }

    public double getMotionHistoryHeadingDelta(int index) {
        return motionHistory.headingDeltaAt(index);
    }

    public double getDistanceLastTicks(int tickCount) {
        return motionHistory.distanceLastTicks(tickCount);
    }

}
