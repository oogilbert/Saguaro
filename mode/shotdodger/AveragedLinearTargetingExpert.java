package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;

final class AveragedLinearTargetingExpert {
    private AveragedLinearTargetingExpert() {
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     double averagedLateralVelocity) {
        validateContext(context, averagedLateralVelocity);
        double absoluteBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double predictedAngle = absoluteBearing + averagedLateralVelocity / context.bulletSpeed;
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        double centerGf = mea > 0.0
                ? MathUtils.angleToGf(absoluteBearing, predictedAngle, mea)
                : 0.0;
        centerGf = Math.max(-1.0, Math.min(1.0, centerGf));
        return new ExpertPrediction(
                new KDEDistribution(new double[] { centerGf }, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH),
                null,
                centerGf);
    }

    private static void validateContext(WaveContextFeatures.WaveContext context,
                                        double averagedLateralVelocity) {
        if (context == null) {
            throw new IllegalArgumentException("Averaged-linear expert requires a non-null context");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Averaged-linear expert requires a positive bullet speed");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(averagedLateralVelocity)) {
            throw new IllegalArgumentException("Averaged-linear expert requires finite source/target state");
        }
    }
}
