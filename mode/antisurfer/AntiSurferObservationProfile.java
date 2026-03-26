package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.ScoreMaxLearningProfile;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.info.wave.WaveLog;
import oog.mega.saguaro.math.BlendedGuessFactorDistribution;
import oog.mega.saguaro.math.GuessFactorDistribution;

final class AntiSurferObservationProfile implements ObservationProfile {
    private static final double ROLLING_DECAY = 0.95;
    private static final double MIN_WEIGHT = 0.05;

    private Info info;
    private double hitAvgDensity = 0.5;
    private double flattenerAvgDensity = 0.25;

    void setInfo(Info info) {
        this.info = info;
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation) {
        ScoreMaxLearningProfile.INSTANCE.logGunInterval(context, gfMin, gfMax, saveObservation);
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation,
                               boolean updateModel) {
        ScoreMaxLearningProfile.INSTANCE.logGunInterval(context, gfMin, gfMax, saveObservation, updateModel);
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation) {
        ScoreMaxLearningProfile.INSTANCE.logMovementResult(context, gf, saveObservation);
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation,
                                  boolean updateModel) {
        ScoreMaxLearningProfile.INSTANCE.logMovementResult(context, gf, saveObservation, updateModel);
    }

    @Override
    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return WaveLog.createAntiSurferGunDistribution(context);
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        GuessFactorDistribution surfDist = WaveLog.createAntiSurferMovementDistribution(context);
        GuessFactorDistribution flatDist = WaveLog.createFlattenerDistribution(context);
        if (surfDist == null && flatDist == null) {
            return null;
        }
        if (surfDist == null) {
            return flatDist;
        }
        if (flatDist == null) {
            return surfDist;
        }
        double surfWeight = Math.max(MIN_WEIGHT, hitAvgDensity);
        double flatWeight = Math.max(MIN_WEIGHT, flattenerAvgDensity);
        return new BlendedGuessFactorDistribution(surfDist, surfWeight, flatDist, flatWeight);
    }

    @Override
    public void onResolvedEnemyWaveHit(WaveContextFeatures.WaveContext context,
                                       double gf) {
        GuessFactorDistribution surfDist = WaveLog.createAntiSurferMovementDistribution(context);
        GuessFactorDistribution flatDist = WaveLog.createFlattenerDistribution(context);
        if (surfDist != null) {
            double surfDensity = surfDist.density(gf);
            hitAvgDensity = ROLLING_DECAY * hitAvgDensity + (1.0 - ROLLING_DECAY) * surfDensity;
        }
        if (flatDist != null) {
            double flatDensity = flatDist.density(gf);
            flattenerAvgDensity = ROLLING_DECAY * flattenerAvgDensity + (1.0 - ROLLING_DECAY) * flatDensity;
        }
        WaveLog.setAntiSurferBlendWeights(
                Math.max(MIN_WEIGHT, hitAvgDensity),
                Math.max(MIN_WEIGHT, flattenerAvgDensity));
        if (info != null && info.getRobot() != null) {
            info.getRobot().out.println("AntiSurfer Blend: " + WaveLog.getAntiSurferBlendSummary());
        }
    }
}
