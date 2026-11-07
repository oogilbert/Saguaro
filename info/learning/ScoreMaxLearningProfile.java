package oog.mega.saguaro.info.learning;

import oog.mega.saguaro.info.learning.rule.RuleProfile;
import oog.mega.saguaro.info.wave.Wave;

import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

public final class ScoreMaxLearningProfile implements ObservationProfile, RoundOutcomeProfile {
    public static final ScoreMaxLearningProfile INSTANCE = new ScoreMaxLearningProfile();

    private ScoreMaxLearningProfile() {
    }

    @Override
    public void startBattle() {
        ScoreMaxScoreHistoryProfile.INSTANCE.startBattle();
        RuleProfile.INSTANCE.startBattle();
    }

    @Override
    public void onRoundStart() {
        RuleProfile.INSTANCE.startRound();
        oog.mega.saguaro.info.learning.rule.RoundWinCalibration.INSTANCE.startRound();
        oog.mega.saguaro.info.learning.rule.HorizonCalibration.INSTANCE.startRound();
        oog.mega.saguaro.mode.scoremax.PlanAudit.startRound();
    }

    @Override
    public void onMyWaveFired(Wave wave) {
        oog.mega.saguaro.mode.scoremax.PlanAudit.onMyWaveFired(wave);
        RuleProfile.INSTANCE.onMyWaveFired(wave);
    }

    @Override
    public void onMyWaveResolved(Wave wave, boolean hit, double gfMin, double gfMax, long time) {
        oog.mega.saguaro.mode.scoremax.PlanAudit.onMyWaveResolved(wave, hit);
        RuleProfile.INSTANCE.onMyWaveResolved(wave, hit, gfMin, gfMax, time);
    }

    @Override
    public void onEnemyWaveResolved(Wave wave, boolean revealed, boolean collided, double bulletGf,
                                    double gfMin, double gfMax, long time) {
        if (collided) oog.mega.saguaro.mode.scoremax.PlanAudit.onEnemyWaveCollided(wave);
        else oog.mega.saguaro.mode.scoremax.PlanAudit.onEnemyWaveResolved(wave, revealed);
        if (collided) oog.mega.saguaro.info.learning.rule.HorizonCalibration.INSTANCE.onCollided(wave);
        else oog.mega.saguaro.info.learning.rule.HorizonCalibration.INSTANCE.onResolved(wave, revealed);
        RuleProfile.INSTANCE.onEnemyWaveResolved(wave, revealed, collided, bulletGf, gfMin, gfMax, time);
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation) {

    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation,
                               boolean updateModel) {

    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation) {

    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation,
                                  boolean updateModel) {

    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation,
                                  boolean updateModel,
                                  boolean actualBulletObservation) {

    }

    @Override
    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return RuleProfile.INSTANCE.createGunDistribution(context);
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return RuleProfile.INSTANCE.createMovementDistribution(context);
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
