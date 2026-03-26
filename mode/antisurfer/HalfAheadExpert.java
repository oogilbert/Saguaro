package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;

final class HalfAheadExpert {
    private HalfAheadExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return createGunPrediction(context).distribution;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return createMovementPrediction(context).distribution;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context) {
        return createPrediction(
                EscapeAheadExpert.createGunPrediction(context),
                context,
                BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH);
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        return createPrediction(
                EscapeAheadExpert.createMovementPrediction(context),
                context,
                BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH);
    }

    private static ExpertPrediction createPrediction(ExpertPrediction fullPrediction,
                                                     WaveContextFeatures.WaveContext context,
                                                     double bandwidth) {
        double halfGf = AntiSurferPreciseMea.clampGf(context, fullPrediction.centerGf * 0.5);
        GuessFactorDistribution distribution = new KDEDistribution(
                new double[]{halfGf},
                bandwidth);
        return new ExpertPrediction(distribution, fullPrediction.trajectory, halfGf);
    }
}
