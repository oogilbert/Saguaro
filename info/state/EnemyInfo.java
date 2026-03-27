package oog.mega.saguaro.info.state;

import java.util.List;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.ConstantDeltaPredictor;
import oog.mega.saguaro.math.GuessFactorDistributionHandle;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import robocode.ScannedRobotEvent;

public class EnemyInfo {
    private static final double INITIAL_GUN_HEAT = 3.0;
    private static final double GUN_COOLING_RATE = 0.1;
    private static final double SYNTHETIC_DEATH_WAVE_POWER = 0.1;
    private static final double GUN_READY_EPSILON = 1e-9;

    public static class PredictedPosition {
        public final double x;
        public final double y;
        public final double heading;
        public final double velocity;
        public final int lastNonZeroLateralDirectionSign;
        public final int accelerationSign;
        public final int ticksSinceVelocityReversal;
        public final int ticksSinceDecel;

        public PredictedPosition(double x,
                                 double y,
                                 double heading,
                                 double velocity,
                                 int lastNonZeroLateralDirectionSign,
                                 int accelerationSign,
                                 int ticksSinceVelocityReversal,
                                 int ticksSinceDecel) {
            this.x = x;
            this.y = y;
            this.heading = heading;
            this.velocity = velocity;
            this.lastNonZeroLateralDirectionSign = lastNonZeroLateralDirectionSign;
            this.accelerationSign = accelerationSign;
            this.ticksSinceVelocityReversal = ticksSinceVelocityReversal;
            this.ticksSinceDecel = ticksSinceDecel;
        }
    }

    public static class UpdateResult {
        public final Wave firedWave;

        public UpdateResult(Wave firedWave) {
            this.firedWave = firedWave;
        }
    }

    public double x;
    public double y;
    public double absoluteBearing;
    public double distance;
    public double energy;
    public double heading;
    public double velocity;
    public long lastScanTime = -1;
    public double lastRobotXAtScan = Double.NaN;
    public double lastRobotYAtScan = Double.NaN;
    public double lastRobotHeadingAtScan = Double.NaN;
    public double lastRobotVelocityAtScan = Double.NaN;
    public double lastRobotHeadingDeltaAtScan = 0.0;
    public double lastRobotVelocityDeltaAtScan = 0.0;
    public int lastRobotAccelerationSignAtScan = 0;
    public int lastRobotTicksSinceVelocityReversalAtScan = 0;
    public int lastRobotTicksSinceDecelAtScan = 0;
    public boolean alive = true;
    public boolean seenThisRound = false;
    public double gunHeat;
    public double lastDetectedBulletPower = Double.NaN;
    public int accelerationSign;
    public int ticksSinceVelocityReversal;
    public int ticksSinceDecel;
    private final RecentMotionHistory motionHistory = new RecentMotionHistory();
    private int lastNonZeroVelocitySign;
    private int lastNonZeroLateralDirectionSign;
    private int lastRobotNonZeroLateralDirectionSignAtScan;
    private double previousXAtScan = Double.NaN;
    private double previousYAtScan = Double.NaN;
    private double previousRobotXAtScan = Double.NaN;
    private double previousRobotYAtScan = Double.NaN;
    private double previousRobotHeadingAtScan = Double.NaN;
    private double previousRobotVelocityAtScan = Double.NaN;
    private double previousHeading = Double.NaN;
    private double previousVelocity = Double.NaN;
    private long previousScanTime = Long.MIN_VALUE;
    private long predictionCacheTargetTime = Long.MIN_VALUE;
    private long predictionCacheScanTime = Long.MIN_VALUE;
    private PredictedPosition predictionCacheValue;

    public EnemyInfo(long currentTime) {
        this.gunHeat = Math.max(0, INITIAL_GUN_HEAT - currentTime * GUN_COOLING_RATE);
    }

    /**
     * Called every tick for the tracked enemy, regardless of whether it was scanned.
     * Handles predictable state changes like gun heat cooling.
     */
    public void updateTick() {
        gunHeat = Math.max(0, gunHeat - GUN_COOLING_RATE);
    }

    public UpdateResult update(Saguaro robot,
                               ScannedRobotEvent e,
                               ObservationProfile observationProfile,
                               double robotHeadingDelta,
                               double robotVelocityDelta,
                               int robotAccelerationSign,
                               int robotTicksSinceVelocityReversal,
                               int robotTicksSinceDecel,
                               List<Wave> existingEnemyWaves) {
        long currentTime = robot.getTime();
        Wave firedWave = null;
        double scannedHeading = e.getHeadingRadians();
        double scannedVelocity = e.getVelocity();
        double headingDeltaSample =
                seenThisRound && lastScanTime == currentTime - 1L
                        ? ConstantDeltaPredictor.estimateHeadingDelta(scannedHeading, heading)
                        : 0.0;

        // Detect enemy shot via energy drop.
        // Timing: Enemy fired at tick T before movement, so they fired from their T-1 position.
        // We scan at tick T and see the energy drop, so enemy waves should be anchored at T-1.
        double energyDrop = energy - e.getEnergy();
        if (seenThisRound && energyDrop > 0) {
            double wallDamage = 0.0;
            if (scannedVelocity == 0.0) {
                double absoluteBearing = robot.getHeadingRadians() + e.getBearingRadians();
                double distance = e.getDistance();
                double newX = robot.getX() + Math.sin(absoluteBearing) * distance;
                double newY = robot.getY() + Math.cos(absoluteBearing) * distance;

                double bfWidth = robot.getBattleFieldWidth();
                double bfHeight = robot.getBattleFieldHeight();
                double margin = PhysicsUtil.WALL_MARGIN;

                boolean hitX = newX <= margin + 1e-4 || newX >= bfWidth - margin - 1e-4;
                boolean hitY = newY <= margin + 1e-4 || newY >= bfHeight - margin - 1e-4;

                if (hitX || hitY) {
                    double attemptedVelocity = 0.0;
                    if (hitX && !hitY) {
                        double cosH = Math.cos(scannedHeading);
                        if (Math.abs(cosH) > 1e-4) {
                            attemptedVelocity = (newY - y) / cosH;
                        }
                    } else if (hitY && !hitX) {
                        double sinH = Math.sin(scannedHeading);
                        if (Math.abs(sinH) > 1e-4) {
                            attemptedVelocity = (newX - x) / sinH;
                        }
                    } else {
                        attemptedVelocity = velocity; // Fallback for corner hits
                    }

                    wallDamage = Math.max(0.0, Math.abs(attemptedVelocity) * 0.5 - 1.0);
                }
            }
            double firepower = energyDrop - wallDamage;
            boolean isFiring = firepower >= 0.099 && firepower <= 3.001 && gunHeat <= 0;
            if (isFiring) {
                firepower = Math.max(0.1, Math.min(3.0, firepower));
                long fireTime = currentTime - 1;
                firedWave = createObservedEnemyWave(
                        robot,
                        existingEnemyWaves,
                        firepower,
                        fireTime,
                        observationProfile.shouldUpdateMovementModel());
                firedWave.fireTimeDistributionHandle = GuessFactorDistributionHandle.orNull(
                        observationProfile.createMovementDistribution(firedWave.fireTimeContext));
                firedWave.fireTimeRecentExpertScores =
                        observationProfile.createMovementRecentPerformanceScores(firedWave.fireTimeContext);
                firedWave.fireTimeRenderGfMarkers =
                        observationProfile.createMovementRenderGfMarkers(firedWave.fireTimeContext);
                firedWave.renderReachableGfInterval = null;
                lastDetectedBulletPower = firepower;
                gunHeat = 1.0 + firepower / 5.0;
            }
        }

        if (seenThisRound) {
            accelerationSign = signWithEpsilon(scannedVelocity - velocity);
            if (Math.abs(scannedVelocity) < Math.abs(velocity) - 1e-9) {
                ticksSinceDecel = 0;
            } else {
                ticksSinceDecel++;
            }
            int currentVelocitySign = signWithEpsilon(scannedVelocity);
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
        } else {
            accelerationSign = 0;
            ticksSinceVelocityReversal = 0;
            ticksSinceDecel = 0;
            int currentVelocitySign = signWithEpsilon(scannedVelocity);
            if (currentVelocitySign != 0) {
                lastNonZeroVelocitySign = currentVelocitySign;
            }
        }

        double priorEnemyX = x;
        double priorEnemyY = y;
        double priorRobotX = lastRobotXAtScan;
        double priorRobotY = lastRobotYAtScan;
        double priorRobotHeading = lastRobotHeadingAtScan;
        double priorRobotVelocity = lastRobotVelocityAtScan;
        double priorEnemyHeading = heading;
        double priorEnemyVelocity = velocity;

        absoluteBearing = robot.getHeadingRadians() + e.getBearingRadians();
        distance = e.getDistance();
        x = robot.getX() + Math.sin(absoluteBearing) * distance;
        y = robot.getY() + Math.cos(absoluteBearing) * distance;
        energy = e.getEnergy();
        if (seenThisRound) {
            previousXAtScan = priorEnemyX;
            previousYAtScan = priorEnemyY;
            previousRobotXAtScan = priorRobotX;
            previousRobotYAtScan = priorRobotY;
            previousRobotHeadingAtScan = priorRobotHeading;
            previousRobotVelocityAtScan = priorRobotVelocity;
            previousHeading = priorEnemyHeading;
            previousVelocity = priorEnemyVelocity;
            previousScanTime = lastScanTime;
        } else {
            previousXAtScan = Double.NaN;
            previousYAtScan = Double.NaN;
            previousRobotXAtScan = Double.NaN;
            previousRobotYAtScan = Double.NaN;
            previousRobotHeadingAtScan = Double.NaN;
            previousRobotVelocityAtScan = Double.NaN;
            previousHeading = Double.NaN;
            previousVelocity = Double.NaN;
            previousScanTime = Long.MIN_VALUE;
        }
        heading = scannedHeading;
        velocity = scannedVelocity;
        lastScanTime = currentTime;
        lastRobotXAtScan = robot.getX();
        lastRobotYAtScan = robot.getY();
        lastRobotHeadingAtScan = robot.getHeadingRadians();
        lastRobotVelocityAtScan = robot.getVelocity();
        lastRobotHeadingDeltaAtScan = robotHeadingDelta;
        lastRobotVelocityDeltaAtScan = robotVelocityDelta;
        lastRobotAccelerationSignAtScan = robotAccelerationSign;
        lastRobotTicksSinceVelocityReversalAtScan = robotTicksSinceVelocityReversal;
        lastRobotTicksSinceDecelAtScan = robotTicksSinceDecel;
        int enemyLateralDirectionSign = WaveContextFeatures.computeLateralDirectionSign(
                robot.getX(),
                robot.getY(),
                x,
                y,
                heading,
                velocity);
        if (enemyLateralDirectionSign != 0) {
            lastNonZeroLateralDirectionSign = enemyLateralDirectionSign;
        }
        int robotLateralDirectionSignAtScan = WaveContextFeatures.computeLateralDirectionSign(
                x,
                y,
                lastRobotXAtScan,
                lastRobotYAtScan,
                lastRobotHeadingAtScan,
                lastRobotVelocityAtScan);
        if (robotLateralDirectionSignAtScan != 0) {
            lastRobotNonZeroLateralDirectionSignAtScan = robotLateralDirectionSignAtScan;
        }
        alive = true;
        seenThisRound = true;
        motionHistory.addSample(scannedVelocity, headingDeltaSample);
        predictionCacheTargetTime = Long.MIN_VALUE;
        predictionCacheScanTime = Long.MIN_VALUE;
        predictionCacheValue = null;

        return new UpdateResult(firedWave);
    }

    public Wave maybeCreateSyntheticDeathWave(Saguaro robot,
                                              ObservationProfile observationProfile,
                                              List<Wave> existingEnemyWaves) {
        if (robot == null || observationProfile == null) {
            throw new IllegalArgumentException(
                    "Synthetic death-wave generation requires non-null robot and observation profile");
        }
        long currentTime = robot.getTime();
        if (!seenThisRound || lastScanTime >= currentTime || gunHeat > GUN_READY_EPSILON) {
            return null;
        }
        // If the enemy fires on the same tick it dies, we can miss the confirming scan-time
        // energy drop entirely. Synthesize a tiny wave so path danger still respects that shot.
        Wave syntheticDeathWave = createObservedEnemyWave(
                robot,
                existingEnemyWaves,
                SYNTHETIC_DEATH_WAVE_POWER,
                currentTime - 1L,
                false);
        syntheticDeathWave.allowMovementObservationLogging = false;
        return syntheticDeathWave;
    }

    private Wave createObservedEnemyWave(Saguaro robot,
                                         List<Wave> existingEnemyWaves,
                                         double firepower,
                                         long fireTime,
                                         boolean allowMovementModelUpdate) {
        if (!Double.isFinite(lastRobotXAtScan) || !Double.isFinite(lastRobotYAtScan)) {
            throw new IllegalStateException("Missing robot scan-time reference for enemy fired wave");
        }
        Wave firedWave = new Wave(x, y, Wave.bulletSpeed(firepower), fireTime, true);
        firedWave.allowMovementModelUpdate = allowMovementModelUpdate;
        // Wave fireTime is T-1, so use the robot position from that same tick.
        firedWave.targetX = lastRobotXAtScan;
        firedWave.targetY = lastRobotYAtScan;
        // Default naive guns fire at T-1 before turning, so their bullet heading
        // is still the gun line they aimed using T-2 scan data.
        firedWave.priorTickTargetX = previousRobotXAtScan;
        firedWave.priorTickTargetY = previousRobotYAtScan;
        firedWave.priorTickTargetHeading = previousRobotHeadingAtScan;
        firedWave.priorTickTargetVelocity = previousRobotVelocityAtScan;
        firedWave.fireTimeTargetHeading = lastRobotHeadingAtScan;
        firedWave.fireTimeTargetVelocity = lastRobotVelocityAtScan;
        firedWave.priorTickShooterX = previousXAtScan;
        firedWave.priorTickShooterY = previousYAtScan;
        firedWave.priorTickShooterHeading = previousHeading;
        firedWave.priorTickShooterVelocity = previousVelocity;
        firedWave.fireTimeShooterHeading = heading;
        firedWave.fireTimeShooterVelocity = velocity;
        // Preserve the prior body turn so shielding can model gun/body coupling.
        firedWave.fireTimeShooterBodyTurn =
                Double.isFinite(previousHeading)
                        ? MathUtils.normalizeAngle(heading - previousHeading)
                        : Double.NaN;
        firedWave.fireTimeContext = WaveContextFeatures.createWaveContext(
                firedWave.originX,
                firedWave.originY,
                firedWave.speed,
                fireTime,
                lastRobotXAtScan,
                lastRobotYAtScan,
                lastRobotHeadingAtScan,
                lastRobotVelocityAtScan,
                lastRobotHeadingDeltaAtScan,
                lastRobotVelocityDeltaAtScan,
                lastRobotAccelerationSignAtScan,
                lastRobotTicksSinceVelocityReversalAtScan,
                lastRobotTicksSinceDecelAtScan,
                lastRobotNonZeroLateralDirectionSignAtScan,
                robot.getBattleFieldWidth(),
                robot.getBattleFieldHeight(),
                existingEnemyWaves,
                null);
        return firedWave;
    }

    /**
     * Predicts enemy position at an absolute game time assuming constant per-tick
     * heading delta and velocity delta inferred from the most recent two scans.
     */
    public PredictedPosition predictPositionAtTime(long targetTime, double battlefieldWidth, double battlefieldHeight) {
        if (!seenThisRound || lastScanTime < 0) {
            throw new IllegalStateException("Cannot predict enemy position before first scan");
        }
        if (predictionCacheValue != null
                && predictionCacheTargetTime == targetTime
                && predictionCacheScanTime == lastScanTime) {
            return predictionCacheValue;
        }

        double predictedX = x;
        double predictedY = y;
        double predictedVelocity = velocity;
        double predictedHeading = heading;
        int predictedAccelerationSign = accelerationSign;
        int predictedTicksSinceVelocityReversal = ticksSinceVelocityReversal;
        int predictedTicksSinceDecel = ticksSinceDecel;
        int predictedLastNonZeroVelocitySign = lastNonZeroVelocitySign;
        double predictedHeadingDelta = getPredictedHeadingDelta();
        double predictedVelocityDelta = getPredictedVelocityDelta();

        long ticks = Math.max(0L, targetTime - lastScanTime);
        if (ticks == 0L) {
            PredictedPosition cached =
                    new PredictedPosition(
                            predictedX,
                            predictedY,
                            predictedHeading,
                            predictedVelocity,
                            lastNonZeroLateralDirectionSign,
                            ticks == 0L ? accelerationSign : 0,
                            ticksSinceVelocityReversal + (int) ticks,
                            ticksSinceDecel + (int) ticks);
            predictionCacheTargetTime = targetTime;
            predictionCacheScanTime = lastScanTime;
            predictionCacheValue = cached;
            return cached;
        }

        for (long i = 0; i < ticks; i++) {
            PhysicsUtil.PositionState nextState = ConstantDeltaPredictor.advance(
                    new PhysicsUtil.PositionState(predictedX, predictedY, predictedHeading, predictedVelocity),
                    predictedHeadingDelta,
                    predictedVelocityDelta,
                    battlefieldWidth,
                    battlefieldHeight);
            predictedAccelerationSign = signWithEpsilon(nextState.velocity - predictedVelocity);
            if (Math.abs(nextState.velocity) < Math.abs(predictedVelocity) - 1e-9) {
                predictedTicksSinceDecel = 0;
            } else {
                predictedTicksSinceDecel++;
            }
            int currentVelocitySign = signWithEpsilon(nextState.velocity);
            if (currentVelocitySign != 0) {
                if (predictedLastNonZeroVelocitySign != 0
                        && currentVelocitySign != predictedLastNonZeroVelocitySign) {
                    predictedTicksSinceVelocityReversal = 0;
                } else {
                    predictedTicksSinceVelocityReversal++;
                }
                predictedLastNonZeroVelocitySign = currentVelocitySign;
            } else {
                predictedTicksSinceVelocityReversal++;
            }
            predictedX = nextState.x;
            predictedY = nextState.y;
            predictedHeading = nextState.heading;
            predictedVelocity = nextState.velocity;
        }

        PredictedPosition prediction =
                new PredictedPosition(
                        predictedX,
                        predictedY,
                        predictedHeading,
                        predictedVelocity,
                        lastNonZeroLateralDirectionSign,
                        predictedAccelerationSign,
                        predictedTicksSinceVelocityReversal,
                        predictedTicksSinceDecel);
        predictionCacheTargetTime = targetTime;
        predictionCacheScanTime = lastScanTime;
        predictionCacheValue = prediction;
        return prediction;
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

    public int getLastNonZeroLateralDirectionSign() {
        return lastNonZeroLateralDirectionSign;
    }

    public int getLastRobotNonZeroLateralDirectionSignAtScan() {
        return lastRobotNonZeroLateralDirectionSignAtScan;
    }

    public double getPredictedHeadingDelta() {
        if (previousScanTime != lastScanTime - 1L) {
            return 0.0;
        }
        return ConstantDeltaPredictor.estimateHeadingDelta(heading, previousHeading);
    }

    public double getPredictedVelocityDelta() {
        if (previousScanTime != lastScanTime - 1L) {
            return 0.0;
        }
        return ConstantDeltaPredictor.estimateVelocityDelta(velocity, previousVelocity);
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
}


