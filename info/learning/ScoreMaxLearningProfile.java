package oog.mega.saguaro.info.learning;

import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.info.wave.WaveLog;

public final class ScoreMaxLearningProfile implements ObservationProfile, RoundOutcomeProfile {
    public static final ScoreMaxLearningProfile INSTANCE = new ScoreMaxLearningProfile();

    private ScoreMaxLearningProfile() {
    }

    @Override
    public void startBattle() {
        ScoreMaxScoreHistoryProfile.INSTANCE.startBattle();
        WaveLog.startBattle();
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation) {
        WaveLog.logGunInterval(context, gfMin, gfMax, saveObservation);
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation,
                               boolean updateModel) {
        WaveLog.logGunInterval(context, gfMin, gfMax, saveObservation, updateModel);
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation) {
        WaveLog.logMovementResult(context, gf, saveObservation);
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation,
                                  boolean updateModel) {
        WaveLog.logMovementResult(context, gf, saveObservation, updateModel);
    }

    @Override
    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return WaveLog.createGunDistribution(context);
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return WaveLog.createMovementDistribution(context);
    }

    @Override
    public int recordWin() {
        return ScoreMaxScoreHistoryProfile.INSTANCE.recordWin();
    }

    @Override
    public int recordLoss() {
        return ScoreMaxScoreHistoryProfile.INSTANCE.recordLoss();
    }

    @Override
    public double getSurvivalPrior() {
        return ScoreMaxScoreHistoryProfile.INSTANCE.getSurvivalPrior();
    }

}
