package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;

final class AntiSurferReachability {
    private static final double ROBOT_MAX_VELOCITY = 8.0;
    private static final double ROBOT_DECELERATION = 2.0;
    private static final double GF_RANGE_OVERLAP_EPSILON = 1e-5;

    private AntiSurferReachability() {
    }

    static double[] computeGunWaveReachableGfInterval(Wave wave,
                                                      EnemyInfo enemy,
                                                      long currentTime,
                                                      double battlefieldWidth,
                                                      double battlefieldHeight) {
        if (wave == null
                || enemy == null
                || !enemy.alive
                || !enemy.seenThisRound
                || wave.fireTimeContext == null
                || Double.isNaN(wave.targetX)
                || Double.isNaN(wave.targetY)) {
            return null;
        }

        int ticksUntilArrival = Math.max(1, PhysicsUtil.waveArrivalTick(
                wave,
                enemy.x,
                enemy.y,
                currentTime));
        double referenceBearing = Math.atan2(wave.targetX - wave.originX, wave.targetY - wave.originY);
        double currentBearing = Math.atan2(enemy.x - wave.originX, enemy.y - wave.originY);
        double currentDistance = Math.max(
                RobotHitbox.HALF_WIDTH,
                Math.hypot(enemy.x - wave.originX, enemy.y - wave.originY));
        double mea = MathUtils.maxEscapeAngle(wave.speed);

        double initialTangentialVelocity = enemy.velocity * FastTrig.sin(enemy.heading - currentBearing);
        double cwDisplacement = simulateTangentialDisplacement(initialTangentialVelocity, 1, ticksUntilArrival);
        double ccwDisplacement = simulateTangentialDisplacement(initialTangentialVelocity, -1, ticksUntilArrival);

        double maxCwWallDistance = maxDistanceInField(
                enemy.x,
                enemy.y,
                currentBearing + Math.PI / 2.0,
                battlefieldWidth,
                battlefieldHeight);
        double maxCcwWallDistance = maxDistanceInField(
                enemy.x,
                enemy.y,
                currentBearing - Math.PI / 2.0,
                battlefieldWidth,
                battlefieldHeight);
        cwDisplacement = Math.min(cwDisplacement, maxCwWallDistance);
        ccwDisplacement = Math.max(ccwDisplacement, -maxCcwWallDistance);

        double cwBearing = currentBearing + Math.atan2(cwDisplacement, currentDistance);
        double ccwBearing = currentBearing + Math.atan2(ccwDisplacement, currentDistance);
        double cwGf = MathUtils.angleToGf(referenceBearing, cwBearing, mea);
        double ccwGf = MathUtils.angleToGf(referenceBearing, ccwBearing, mea);

        double[] approximateRange = orderFiniteGfRange(cwGf, ccwGf);
        double[] preciseRange = MathUtils.inFieldMaxEscapeGfRange(
                wave.originX,
                wave.originY,
                wave.targetX,
                wave.targetY,
                wave.speed,
                battlefieldWidth,
                battlefieldHeight);
        double clippedMinGf = Math.max(approximateRange[0], preciseRange[0]);
        double clippedMaxGf = Math.min(approximateRange[1], preciseRange[1]);
        if (clippedMinGf > clippedMaxGf) {
            double gap = clippedMinGf - clippedMaxGf;
            if (gap <= GF_RANGE_OVERLAP_EPSILON) {
                double collapsed = (clippedMinGf + clippedMaxGf) * 0.5;
                clippedMinGf = collapsed;
                clippedMaxGf = collapsed;
            } else if (approximateRange[1] <= preciseRange[0]) {
                clippedMinGf = preciseRange[0];
                clippedMaxGf = preciseRange[0];
            } else if (approximateRange[0] >= preciseRange[1]) {
                clippedMinGf = preciseRange[1];
                clippedMaxGf = preciseRange[1];
            } else {
                throw new IllegalStateException(
                        "Approximate and precise AntiSurfer GF ranges do not overlap: "
                                + "approximate=[" + approximateRange[0] + ", " + approximateRange[1] + "], "
                                + "precise=[" + preciseRange[0] + ", " + preciseRange[1] + "]");
            }
        }
        return normalizeGfRange(clippedMinGf, clippedMaxGf);
    }

    private static double simulateTangentialDisplacement(double initialVelocity,
                                                         int commandSign,
                                                         int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        double velocity = clamp(initialVelocity, -ROBOT_MAX_VELOCITY, ROBOT_MAX_VELOCITY);
        double displacement = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            velocity = stepVelocity(velocity, commandSign);
            displacement += velocity;
        }
        return displacement;
    }

    private static double stepVelocity(double currentVelocity, int commandSign) {
        double move = commandSign;
        if (currentVelocity == 0.0) {
            return move;
        }
        if (Math.signum(currentVelocity) == move) {
            return clamp(currentVelocity + move, -ROBOT_MAX_VELOCITY, ROBOT_MAX_VELOCITY);
        }
        double absVelocity = Math.abs(currentVelocity);
        if (absVelocity >= ROBOT_DECELERATION) {
            return currentVelocity + ROBOT_DECELERATION * move;
        }
        double stoppingFraction = absVelocity / ROBOT_DECELERATION;
        return currentVelocity
                + ROBOT_DECELERATION * move * stoppingFraction
                + move * (1.0 - stoppingFraction);
    }

    private static double maxDistanceInField(double x,
                                             double y,
                                             double angle,
                                             double battlefieldWidth,
                                             double battlefieldHeight) {
        double minX = PhysicsUtil.WALL_MARGIN;
        double maxX = battlefieldWidth - PhysicsUtil.WALL_MARGIN;
        double minY = PhysicsUtil.WALL_MARGIN;
        double maxY = battlefieldHeight - PhysicsUtil.WALL_MARGIN;
        double dirX = FastTrig.sin(angle);
        double dirY = FastTrig.cos(angle);
        double tx = Double.POSITIVE_INFINITY;
        if (dirX > 1e-9) {
            tx = (maxX - x) / dirX;
        } else if (dirX < -1e-9) {
            tx = (minX - x) / dirX;
        }
        double ty = Double.POSITIVE_INFINITY;
        if (dirY > 1e-9) {
            ty = (maxY - y) / dirY;
        } else if (dirY < -1e-9) {
            ty = (minY - y) / dirY;
        }
        double maxDistance = Math.min(tx, ty);
        return Double.isFinite(maxDistance) ? Math.max(0.0, maxDistance) : 0.0;
    }

    private static double[] normalizeGfRange(double firstGf, double secondGf) {
        double minGf = Math.max(-1.0, Math.min(1.0, Math.min(firstGf, secondGf)));
        double maxGf = Math.max(-1.0, Math.min(1.0, Math.max(firstGf, secondGf)));
        return new double[]{minGf, maxGf};
    }

    private static double[] orderFiniteGfRange(double firstGf, double secondGf) {
        if (!Double.isFinite(firstGf) || !Double.isFinite(secondGf)) {
            throw new IllegalStateException(
                    "Non-finite AntiSurfer reachable GF endpoint: firstGf="
                            + firstGf + ", secondGf=" + secondGf);
        }
        return new double[]{
                Math.min(firstGf, secondGf),
                Math.max(firstGf, secondGf)
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
