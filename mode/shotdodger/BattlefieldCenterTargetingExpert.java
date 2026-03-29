package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;

final class BattlefieldCenterTargetingExpert {
    private BattlefieldCenterTargetingExpert() {
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        validateContext(context);
        double absoluteBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double centerX = context.battlefieldWidth * 0.5;
        double centerY = context.battlefieldHeight * 0.5;
        double predictedAngle = Math.atan2(centerX - context.sourceX, centerY - context.sourceY);
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        // Keep the raw GF so the existing angle transforms can preserve an
        // intentionally out-of-range "aim at center" shot before later stages
        // decide how to score or render it.
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
            throw new IllegalArgumentException("Battlefield-center expert requires a non-null context");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Battlefield-center expert requires a positive bullet speed");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(context.battlefieldWidth)
                || !Double.isFinite(context.battlefieldHeight)) {
            throw new IllegalArgumentException("Battlefield-center expert requires finite source/target state");
        }
    }
}
