package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;

final class DroidImpactHeadingExpert {
    private DroidImpactHeadingExpert() {
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     double latestHitBulletReturnHeading) {
        validateContext(context);
        if (!Double.isFinite(latestHitBulletReturnHeading)) {
            return BattlefieldCenterTargetingExpert.createMovementPrediction(context);
        }

        double absoluteBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        double centerGf = mea > 0.0
                ? MathUtils.angleToGf(absoluteBearing, latestHitBulletReturnHeading, mea)
                : 0.0;
        return new ExpertPrediction(
                new KDEDistribution(new double[] { centerGf }, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH),
                null,
                centerGf);
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Droid-impact-heading expert requires a non-null context");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Droid-impact-heading expert requires a positive bullet speed");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(context.battlefieldWidth)
                || !Double.isFinite(context.battlefieldHeight)) {
            throw new IllegalArgumentException("Droid-impact-heading expert requires finite source/target state");
        }
    }
}
