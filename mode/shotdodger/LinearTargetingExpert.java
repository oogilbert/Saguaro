package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;

final class LinearTargetingExpert {
    private LinearTargetingExpert() {
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        validateContext(context);
        double absoluteBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double predictedAngle = absoluteBearing
                + context.targetVelocity * Math.sin(context.targetHeading - absoluteBearing) / context.bulletSpeed;
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        double centerGf = mea > 0.0
                ? MathUtils.angleToGf(absoluteBearing, predictedAngle, mea)
                : 0.0;
        return new ExpertPrediction(
                new KDEDistribution(new double[] { centerGf }, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH),
                null,
                centerGf);
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Linear-targeting expert requires a non-null context");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Linear-targeting expert requires a positive bullet speed");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(context.targetHeading)
                || !Double.isFinite(context.targetVelocity)) {
            throw new IllegalArgumentException("Linear-targeting expert requires finite source/target state");
        }
    }
}
