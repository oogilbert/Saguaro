package oog.mega.saguaro.info.wave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.math.GuessFactorDistributionHandle;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.RobotHitbox;
import robocode.Bullet;

public final class Wave {
    public final double originX;
    public final double originY;
    public final double speed;
    public final long fireTime;
    public final boolean isEnemy;
    public final boolean isVirtual;
    public final double heading;
    public final Bullet bullet;
    public double targetX = Double.NaN;
    public double targetY = Double.NaN;
    public double priorTickTargetX = Double.NaN;
    public double priorTickTargetY = Double.NaN;
    public double priorTickTargetHeading = Double.NaN;
    public double priorTickTargetVelocity = Double.NaN;
    public double fireTimeTargetHeading = Double.NaN;
    public double fireTimeTargetVelocity = Double.NaN;
    public double priorTickShooterX = Double.NaN;
    public double priorTickShooterY = Double.NaN;
    public double priorTickShooterHeading = Double.NaN;
    public double priorTickShooterVelocity = Double.NaN;
    public double fireTimeShooterHeading = Double.NaN;
    public double fireTimeShooterVelocity = Double.NaN;
    public double fireTimeShooterBodyTurn = Double.NaN;
    public WaveContextFeatures.WaveContext fireTimeContext;
    public GuessFactorDistributionHandle fireTimeDistributionHandle;
    public double[] fireTimeRenderGfMarkers;
    public double[] renderReachableGfInterval;
    public boolean allowTargetingModelUpdate;
    public boolean allowMovementModelUpdate;
    public boolean allowMovementObservationLogging;
    private final List<BulletShadowUtil.ShadowInterval> shadowIntervals = new ArrayList<>();
    private final List<BulletShadowUtil.ShadowInterval> shadowIntervalsView =
            Collections.unmodifiableList(shadowIntervals);

    public Wave(double originX, double originY, double speed, long fireTime, boolean isEnemy) {
        this(originX, originY, speed, fireTime, isEnemy, Double.NaN, null, false);
    }

    public Wave(double originX, double originY, double speed, long fireTime, boolean isEnemy, boolean isVirtual) {
        this(originX, originY, speed, fireTime, isEnemy, Double.NaN, null, isVirtual);
    }

    public Wave(double originX, double originY, double speed, long fireTime, boolean isEnemy, double heading) {
        this(originX, originY, speed, fireTime, isEnemy, heading, null, false);
    }

    public Wave(double originX, double originY, double speed, long fireTime, boolean isEnemy, double heading, Bullet bullet) {
        this(originX, originY, speed, fireTime, isEnemy, heading, bullet, false);
    }

    public Wave(double originX, double originY, double speed, long fireTime, boolean isEnemy, double heading, Bullet bullet, boolean isVirtual) {
        this.originX = originX;
        this.originY = originY;
        this.speed = speed;
        this.fireTime = fireTime;
        this.isEnemy = isEnemy;
        this.isVirtual = isVirtual;
        this.heading = heading;
        this.bullet = bullet;
        this.allowMovementObservationLogging = true;
    }

    public double getBulletX(long currentTime) {
        double radius = getRadius(currentTime);
        return originX + FastTrig.sin(heading) * radius;
    }

    public double getBulletY(long currentTime) {
        double radius = getRadius(currentTime);
        return originY + FastTrig.cos(heading) * radius;
    }

    public double getRadius(long currentTime) {
        return (currentTime - fireTime) * speed;
    }

    /**
     * Returns true when the wavefront has reached any part of the bot body.
     */
    public boolean hasHit(double x, double y, long currentTime) {
        double radius = getRadius(currentTime);
        if (radius < 0.0) {
            return false;
        }
        return RobotHitbox.waveHasReached(originX, originY, radius, x, y);
    }

    public boolean hasPassed(double x, double y, long currentTime) {
        double radius = getRadius(currentTime);
        if (radius <= 0.0) {
            return false;
        }
        // Consider a wave "passed" only after the wavefront has cleared the bot body.
        return RobotHitbox.waveHasPassed(originX, originY, radius, x, y);
    }

    public boolean intersectsBodyAtRadius(double x, double y, double radius) {
        return RobotHitbox.intersectsWave(originX, originY, radius, x, y);
    }

    public double minDistanceToBody(double x, double y) {
        return RobotHitbox.minDistance(originX, originY, x, y);
    }

    public double maxDistanceToBody(double x, double y) {
        return RobotHitbox.maxDistance(originX, originY, x, y);
    }

    public static double bulletSpeed(double firepower) {
        return 20.0 - 3.0 * firepower;
    }

    public static int nominalFlightTicks(double distance, double bulletSpeed) {
        return Math.max(1, (int) Math.ceil(distance / bulletSpeed));
    }

    public double getFirepower() {
        return (20.0 - speed) / 3.0;
    }

    public void addShadowIntervals(List<BulletShadowUtil.ShadowInterval> intervals) {
        if (!isEnemy || isVirtual) {
            throw new IllegalStateException("Only real enemy waves can store persistent shadows");
        }
        if (intervals == null || intervals.isEmpty()) {
            return;
        }
        shadowIntervals.addAll(intervals);
    }

    public List<BulletShadowUtil.ShadowInterval> getShadowIntervals() {
        if (!isEnemy || isVirtual) {
            throw new IllegalStateException("Only real enemy waves expose persistent shadows");
        }
        return shadowIntervalsView;
    }
}


