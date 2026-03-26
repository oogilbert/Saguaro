package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

final class AntiSurferObservationProfile implements ObservationProfile {
    private Info info;

    void setInfo(Info info) {
        this.info = info;
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
        ExpertPrediction prediction = ActiveAntiSurferExpert.createGunPrediction(
                context,
                info != null ? info.getEnemy() : null,
                info);
        return prediction != null ? prediction.distribution : null;
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        ExpertPrediction prediction = ActiveAntiSurferExpert.createMovementPrediction(context, info);
        return prediction != null ? prediction.distribution : null;
    }

    @Override
    public double[] createGunRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        return ActiveAntiSurferExpert.createGunCenters(
                context,
                info != null ? info.getEnemy() : null,
                info);
    }

    @Override
    public double[] createMovementRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        return ActiveAntiSurferExpert.createMovementCenters(context, info);
    }

    @Override
    public boolean shouldUpdateTargetingModel() {
        return false;
    }

    @Override
    public boolean shouldUpdateMovementModel() {
        return false;
    }
}
