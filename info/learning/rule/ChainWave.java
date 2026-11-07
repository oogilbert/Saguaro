package oog.mega.saguaro.info.learning.rule;

import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.MathUtils;

/**
 * One of our waves as the opponent model reads it: where and when it was fired, how
 * fast, and the canonical guess-factor frame the opponent's rating is indexed in for
 * it -- Saguaro's frame about the bearing to the opponent at fire time, mirrored so the
 * opponent's momentum points positive. A prospective wave is one not fired yet.
 */
final class ChainWave {
    /** The opponent's motion state at a scan. */
    record Mover(double x, double y, double heading, double velocity) { }

    final double originX, originY, speed, heading;
    final long fireTime;
    final double referenceBearing, mea;
    final boolean mirror;
    final WaveContextFeatures.WaveContext context;

    ChainWave(WaveContextFeatures.WaveContext context, long fireTime, double heading) {
        this.context = context;
        this.originX = context.sourceX;
        this.originY = context.sourceY;
        this.speed = context.bulletSpeed;
        this.fireTime = fireTime;
        this.heading = heading;
        this.referenceBearing = Math.atan2(context.targetX - context.sourceX, context.targetY - context.sourceY);
        this.mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        this.mirror = WaveContextFeatures.normalizeDirectionSign(context.momentumDirectionSign) < 0;
    }

    /** Canonical guess factor of an absolute angle, unclamped. */
    double gf(double angle) {
        double raw = MathUtils.angleToGf(referenceBearing, angle, mea);
        return mirror ? -raw : raw;
    }

    /** Absolute angle of a canonical guess factor. */
    double angle(double gf) {
        return referenceBearing + (mirror ? -gf : gf) * mea;
    }

    /** The rating cell an absolute angle falls in. */
    int cell(double angle) {
        double g = Math.max(-1, Math.min(1, gf(angle)));
        return (int) Math.round((g + 1) * (RuleModel.BINS - 1) / 2.0);
    }

    double radius(long time) { return (time - fireTime) * speed; }
}
