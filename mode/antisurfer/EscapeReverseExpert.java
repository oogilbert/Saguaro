package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

final class EscapeReverseExpert {
    private EscapeReverseExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return createGunPrediction(context).distribution;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return createMovementPrediction(context).distribution;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context) {
        return EscapeAheadExpert.createPredictionForOrbitDirection(
                context,
                BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH,
                -EscapeAheadExpert.resolveOrbitDirectionSign(context));
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        return EscapeAheadExpert.createPredictionForOrbitDirection(
                context,
                BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH,
                -EscapeAheadExpert.resolveOrbitDirectionSign(context));
    }
}
