package oog.mega.saguaro.info.learning;

import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

public final class NoOpLearningProfile implements ObservationProfile, RoundOutcomeProfile {
    private static final double DEFAULT_SURVIVAL_PRIOR = 0.5;

    public static final NoOpLearningProfile INSTANCE = new NoOpLearningProfile();

    private NoOpLearningProfile() {
    }

    @Override
    public void startBattle() {
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation) {
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation) {
    }

    @Override
    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return null;
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return null;
    }

    @Override
    public int recordWin() {
        return 0;
    }

    @Override
    public int recordLoss() {
        return 0;
    }

    @Override
    public double getSurvivalPrior() {
        return DEFAULT_SURVIVAL_PRIOR;
    }

}
