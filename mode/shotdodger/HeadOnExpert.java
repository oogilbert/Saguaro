package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;

final class HeadOnExpert {
    private HeadOnExpert() {
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        validateContext(context);
        return new ExpertPrediction(
                new KDEDistribution(new double[]{0.0}, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH),
                null,
                0.0);
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Head-on expert requires a non-null context");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Head-on expert requires a positive bullet speed");
        }
    }
}
