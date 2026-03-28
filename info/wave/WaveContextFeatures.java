package oog.mega.saguaro.info.wave;

import java.util.List;

import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;

public final class WaveContextFeatures {
    static final double ROBOT_DIAMETER = 36.0;
    private static final double STICK_FEATURE_TARGET_DISTANCE = 1000.0;

    public static final class WaveContext {
        public final double sourceX;
        public final double sourceY;
        public final double bulletSpeed;
        public final double targetX;
        public final double targetY;
        public final double targetHeading;
        public final double targetVelocity;
        public final double targetHeadingDelta;
        public final double targetVelocityDelta;
        public final double battlefieldWidth;
        public final double battlefieldHeight;
        public final double lateralVelocity;
        public final int lateralDirectionSign;
        public final double advancingVelocity;
        public final int flightTicks;
        public final int accelerationSign;
        public final int ticksSinceVelocityReversal;
        public final int ticksSinceDecel;
        public final double wallAhead;
        public final double wallReverse;
        public final double currentGF;
        public final double relativeHeading;
        public final double absoluteVelocity;
        public final double distanceLast10;
        public final double distanceLast20;
        public final double stickWallAhead;
        public final double stickWallReverse;
        public final double stickWallAhead2;
        public final double stickWallReverse2;
        public final boolean didHit;
        public final long battleTime;

        public WaveContext(double sourceX,
                           double sourceY,
                           double bulletSpeed,
                           double targetX,
                           double targetY,
                           double targetHeading,
                           double targetVelocity,
                           double targetHeadingDelta,
                           double targetVelocityDelta,
                           double battlefieldWidth,
                           double battlefieldHeight,
                           double lateralVelocity,
                           int lateralDirectionSign,
                           double advancingVelocity,
                           int flightTicks,
                           int accelerationSign,
                           int ticksSinceVelocityReversal,
                           int ticksSinceDecel,
                           double wallAhead,
                           double wallReverse,
                           double currentGF,
                           double relativeHeading,
                           double absoluteVelocity,
                           double distanceLast10,
                           double distanceLast20,
                           double stickWallAhead,
                           double stickWallReverse,
                           double stickWallAhead2,
                           double stickWallReverse2,
                           boolean didHit,
                           long battleTime) {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.bulletSpeed = bulletSpeed;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetHeading = targetHeading;
            this.targetVelocity = targetVelocity;
            this.targetHeadingDelta = targetHeadingDelta;
            this.targetVelocityDelta = targetVelocityDelta;
            this.battlefieldWidth = battlefieldWidth;
            this.battlefieldHeight = battlefieldHeight;
            this.lateralVelocity = lateralVelocity;
            this.lateralDirectionSign = lateralDirectionSign;
            this.advancingVelocity = advancingVelocity;
            this.flightTicks = flightTicks;
            this.accelerationSign = accelerationSign;
            this.ticksSinceVelocityReversal = ticksSinceVelocityReversal;
            this.ticksSinceDecel = ticksSinceDecel;
            this.wallAhead = wallAhead;
            this.wallReverse = wallReverse;
            this.currentGF = currentGF;
            this.relativeHeading = relativeHeading;
            this.absoluteVelocity = absoluteVelocity;
            this.distanceLast10 = distanceLast10;
            this.distanceLast20 = distanceLast20;
            this.stickWallAhead = stickWallAhead;
            this.stickWallReverse = stickWallReverse;
            this.stickWallAhead2 = stickWallAhead2;
            this.stickWallReverse2 = stickWallReverse2;
            this.didHit = didHit;
            this.battleTime = battleTime;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof WaveContext)) {
                return false;
            }
            WaveContext other = (WaveContext) obj;
            return Double.doubleToLongBits(sourceX) == Double.doubleToLongBits(other.sourceX)
                    && Double.doubleToLongBits(sourceY) == Double.doubleToLongBits(other.sourceY)
                    && Double.doubleToLongBits(bulletSpeed) == Double.doubleToLongBits(other.bulletSpeed)
                    && Double.doubleToLongBits(targetX) == Double.doubleToLongBits(other.targetX)
                    && Double.doubleToLongBits(targetY) == Double.doubleToLongBits(other.targetY)
                    && Double.doubleToLongBits(targetHeading) == Double.doubleToLongBits(other.targetHeading)
                    && Double.doubleToLongBits(targetVelocity) == Double.doubleToLongBits(other.targetVelocity)
                    && Double.doubleToLongBits(targetHeadingDelta) == Double.doubleToLongBits(other.targetHeadingDelta)
                    && Double.doubleToLongBits(targetVelocityDelta) == Double.doubleToLongBits(other.targetVelocityDelta)
                    && Double.doubleToLongBits(battlefieldWidth) == Double.doubleToLongBits(other.battlefieldWidth)
                    && Double.doubleToLongBits(battlefieldHeight) == Double.doubleToLongBits(other.battlefieldHeight)
                    && Double.doubleToLongBits(lateralVelocity) == Double.doubleToLongBits(other.lateralVelocity)
                    && lateralDirectionSign == other.lateralDirectionSign
                    && Double.doubleToLongBits(advancingVelocity) == Double.doubleToLongBits(other.advancingVelocity)
                    && flightTicks == other.flightTicks
                    && accelerationSign == other.accelerationSign
                    && ticksSinceVelocityReversal == other.ticksSinceVelocityReversal
                    && ticksSinceDecel == other.ticksSinceDecel
                    && Double.doubleToLongBits(wallAhead) == Double.doubleToLongBits(other.wallAhead)
                    && Double.doubleToLongBits(wallReverse) == Double.doubleToLongBits(other.wallReverse)
                    && Double.doubleToLongBits(currentGF) == Double.doubleToLongBits(other.currentGF)
                    && Double.doubleToLongBits(relativeHeading) == Double.doubleToLongBits(other.relativeHeading)
                    && Double.doubleToLongBits(absoluteVelocity) == Double.doubleToLongBits(other.absoluteVelocity)
                    && Double.doubleToLongBits(distanceLast10) == Double.doubleToLongBits(other.distanceLast10)
                    && Double.doubleToLongBits(distanceLast20) == Double.doubleToLongBits(other.distanceLast20)
                    && Double.doubleToLongBits(stickWallAhead) == Double.doubleToLongBits(other.stickWallAhead)
                    && Double.doubleToLongBits(stickWallReverse) == Double.doubleToLongBits(other.stickWallReverse)
                    && Double.doubleToLongBits(stickWallAhead2) == Double.doubleToLongBits(other.stickWallAhead2)
                    && Double.doubleToLongBits(stickWallReverse2) == Double.doubleToLongBits(other.stickWallReverse2)
                    && didHit == other.didHit
                    && battleTime == other.battleTime;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(Double.doubleToLongBits(sourceX));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(sourceY));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(bulletSpeed));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(targetX));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(targetY));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(targetHeading));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(targetVelocity));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(targetHeadingDelta));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(targetVelocityDelta));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(battlefieldWidth));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(battlefieldHeight));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(lateralVelocity));
            result = 31 * result + lateralDirectionSign;
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(advancingVelocity));
            result = 31 * result + flightTicks;
            result = 31 * result + accelerationSign;
            result = 31 * result + ticksSinceVelocityReversal;
            result = 31 * result + ticksSinceDecel;
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(wallAhead));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(wallReverse));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(currentGF));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(relativeHeading));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(absoluteVelocity));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(distanceLast10));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(distanceLast20));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(stickWallAhead));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(stickWallReverse));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(stickWallAhead2));
            result = 31 * result + Long.hashCode(Double.doubleToLongBits(stickWallReverse2));
            result = 31 * result + (didHit ? 1 : 0);
            result = 31 * result + Long.hashCode(battleTime);
            return result;
        }
    }

    private WaveContextFeatures() {
    }

    public static WaveContext createWaveContext(double sourceX,
                                                double sourceY,
                                                double bulletSpeed,
                                                long currentTime,
                                                double targetX,
                                                double targetY,
                                                double targetHeading,
                                                double targetVelocity,
                                                double targetHeadingDelta,
                                                double targetVelocityDelta,
                                                int accelerationSign,
                                                int ticksSinceVelocityReversal,
                                                int ticksSinceDecel,
                                                double distanceLast10,
                                                double distanceLast20,
                                                boolean didHit,
                                                int lastNonZeroLateralDirectionSign,
                                                double battlefieldWidth,
                                                double battlefieldHeight,
                                                List<Wave> referenceWaves,
                                                Wave excludedReferenceWave) {
        double bearing = Math.atan2(targetX - sourceX, targetY - sourceY);
        double lateralVelocity = targetVelocity * Math.sin(targetHeading - bearing);
        int lateralDirectionSign = resolveLateralDirectionSign(lateralVelocity, lastNonZeroLateralDirectionSign);
        double advancingVelocity = -targetVelocity * Math.cos(targetHeading - bearing);
        double distance = Math.hypot(targetX - sourceX, targetY - sourceY);
        int flightTicks = Wave.nominalFlightTicks(distance, bulletSpeed);
        double[] wallFeatures = computeDirectionalWallFeatures(
                sourceX,
                sourceY,
                targetX,
                targetY,
                bulletSpeed,
                battlefieldWidth,
                battlefieldHeight,
                lateralDirectionSign);
        double currentGF = computeCurrentGuessFactor(
                referenceWaves,
                excludedReferenceWave,
                targetX,
                targetY,
                currentTime);
        double relativeHeading = MathUtils.normalizeAngle(targetHeading - bearing);
        double[] stickWallFeatures = computeStickWallFeatures(
                sourceX,
                sourceY,
                targetX,
                targetY,
                targetHeading,
                battlefieldWidth,
                battlefieldHeight,
                lateralDirectionSign);
        return new WaveContext(
                sourceX,
                sourceY,
                bulletSpeed,
                targetX,
                targetY,
                targetHeading,
                targetVelocity,
                targetHeadingDelta,
                targetVelocityDelta,
                battlefieldWidth,
                battlefieldHeight,
                lateralVelocity,
                lateralDirectionSign,
                advancingVelocity,
                flightTicks,
                accelerationSign,
                ticksSinceVelocityReversal,
                ticksSinceDecel,
                wallFeatures[0],
                wallFeatures[1],
                currentGF,
                relativeHeading,
                Math.abs(targetVelocity),
                Math.max(0.0, distanceLast10),
                Math.max(0.0, distanceLast20),
                stickWallFeatures[0],
                stickWallFeatures[1],
                stickWallFeatures[2],
                stickWallFeatures[3],
                didHit,
                currentTime);
    }

    public static double computeCurrentGuessFactor(List<Wave> referenceWaves,
                                                   Wave excludedReferenceWave,
                                                   double targetX,
                                                   double targetY,
                                                   long currentTime) {
        if (referenceWaves == null || referenceWaves.isEmpty()) {
            return 0.0;
        }
        Wave closestWave = null;
        int bestArrivalTick = Integer.MAX_VALUE;
        for (Wave wave : referenceWaves) {
            if (wave == null || wave == excludedReferenceWave) {
                continue;
            }
            if (currentTime < wave.fireTime || Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
                continue;
            }
            if (wave.hasPassed(targetX, targetY, currentTime)) {
                continue;
            }
            int arrivalTick = PhysicsUtil.waveArrivalTick(wave, targetX, targetY, currentTime);
            if (arrivalTick < bestArrivalTick) {
                bestArrivalTick = arrivalTick;
                closestWave = wave;
            }
        }
        if (closestWave == null) {
            return 0.0;
        }
        double referenceBearing = Math.atan2(
                closestWave.targetX - closestWave.originX,
                closestWave.targetY - closestWave.originY);
        double currentBearing = Math.atan2(
                targetX - closestWave.originX,
                targetY - closestWave.originY);
        double currentGF = MathUtils.angleToGf(
                referenceBearing,
                currentBearing,
                MathUtils.maxEscapeAngle(closestWave.speed));
        return clamp(currentGF, -1.0, 1.0);
    }

    public static int computeLateralDirectionSign(double sourceX,
                                                  double sourceY,
                                                  double targetX,
                                                  double targetY,
                                                  double targetHeading,
                                                  double targetVelocity) {
        double bearing = Math.atan2(targetX - sourceX, targetY - sourceY);
        double lateralVelocity = targetVelocity * Math.sin(targetHeading - bearing);
        return signWithEpsilon(lateralVelocity);
    }

    public static int approximateLateralDirectionSign(double sourceX,
                                                      double sourceY,
                                                      double targetX,
                                                      double targetY,
                                                      double targetHeading,
                                                      int lastNonZeroVelocitySign) {
        int directionSign = normalizeDirectionSign(lastNonZeroVelocitySign);
        if (directionSign == 0) {
            return 0;
        }
        double bearing = Math.atan2(targetX - sourceX, targetY - sourceY);
        return signWithEpsilon(directionSign * Math.sin(targetHeading - bearing));
    }

    public static int normalizeDirectionSign(int directionSign) {
        if (directionSign > 0) {
            return 1;
        }
        if (directionSign < 0) {
            return -1;
        }
        return 0;
    }

    private static double[] computeDirectionalWallFeatures(double sourceX,
                                                           double sourceY,
                                                           double targetX,
                                                           double targetY,
                                                           double bulletSpeed,
                                                           double battlefieldWidth,
                                                           double battlefieldHeight,
                                                           int lateralDirectionSign) {
        double[] preciseRange = MathUtils.inFieldMaxEscapeGfRange(
                sourceX,
                sourceY,
                targetX,
                targetY,
                bulletSpeed,
                battlefieldWidth,
                battlefieldHeight);
        double negativeEscape = clamp(-preciseRange[0], 0.0, 1.0);
        double positiveEscape = clamp(preciseRange[1], 0.0, 1.0);
        if (lateralDirectionSign >= 0) {
            return new double[]{positiveEscape, negativeEscape};
        }
        return new double[]{negativeEscape, positiveEscape};
    }

    private static double[] computeStickWallFeatures(double sourceX,
                                                     double sourceY,
                                                     double targetX,
                                                     double targetY,
                                                     double targetHeading,
                                                     double battlefieldWidth,
                                                     double battlefieldHeight,
                                                     int lateralDirectionSign) {
        int directionSign = normalizeDirectionSign(lateralDirectionSign);
        if (directionSign == 0) {
            directionSign = 1;
        }
        double absoluteBearing = Math.atan2(targetX - sourceX, targetY - sourceY);
        double aheadHeading = MathUtils.normalizeAngle(absoluteBearing + directionSign * Math.PI * 0.5);
        double reverseHeading = MathUtils.normalizeAngle(absoluteBearing - directionSign * Math.PI * 0.5);
        PhysicsUtil.SteeringMode primaryAheadMode =
                directionSign > 0 ? PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW : PhysicsUtil.SteeringMode.WALL_SMOOTHED_CCW;
        PhysicsUtil.SteeringMode primaryReverseMode =
                directionSign > 0 ? PhysicsUtil.SteeringMode.WALL_SMOOTHED_CCW : PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW;
        PhysicsUtil.SteeringMode secondaryAheadMode =
                primaryAheadMode == PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW
                        ? PhysicsUtil.SteeringMode.WALL_SMOOTHED_CCW
                        : PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW;
        PhysicsUtil.SteeringMode secondaryReverseMode =
                primaryReverseMode == PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW
                        ? PhysicsUtil.SteeringMode.WALL_SMOOTHED_CCW
                        : PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW;
        return new double[]{
                wallSmoothingDeviation(targetX, targetY, aheadHeading, primaryAheadMode, battlefieldWidth, battlefieldHeight),
                wallSmoothingDeviation(targetX, targetY, reverseHeading, primaryReverseMode, battlefieldWidth, battlefieldHeight),
                wallSmoothingDeviation(targetX, targetY, aheadHeading, secondaryAheadMode, battlefieldWidth, battlefieldHeight),
                wallSmoothingDeviation(targetX, targetY, reverseHeading, secondaryReverseMode, battlefieldWidth, battlefieldHeight)
        };
    }

    private static double wallSmoothingDeviation(double x,
                                                 double y,
                                                 double desiredHeading,
                                                 PhysicsUtil.SteeringMode steeringMode,
                                                 double battlefieldWidth,
                                                 double battlefieldHeight) {
        double targetX = x + Math.sin(desiredHeading) * STICK_FEATURE_TARGET_DISTANCE;
        double targetY = y + Math.cos(desiredHeading) * STICK_FEATURE_TARGET_DISTANCE;
        double smoothedHeading = PhysicsUtil.computeTravelAngle(
                x,
                y,
                targetX,
                targetY,
                steeringMode,
                battlefieldWidth,
                battlefieldHeight);
        if (!Double.isFinite(smoothedHeading)) {
            return 1.0;
        }
        return clamp(
                Math.abs(MathUtils.normalizeAngle(smoothedHeading - desiredHeading)) / (Math.PI * 0.5),
                0.0,
                1.0);
    }

    private static int resolveLateralDirectionSign(double lateralVelocity, int lastNonZeroLateralDirectionSign) {
        int directionSign = signWithEpsilon(lateralVelocity);
        if (directionSign != 0) {
            return directionSign;
        }
        directionSign = normalizeDirectionSign(lastNonZeroLateralDirectionSign);
        return directionSign != 0 ? directionSign : 1;
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
