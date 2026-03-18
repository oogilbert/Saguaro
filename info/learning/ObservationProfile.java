package oog.mega.saguaro.info.learning;

import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

public interface ObservationProfile {
    default void logGunResult(WaveContextFeatures.WaveContext context,
                              double gf) {
        logGunInterval(context, gf, gf, true);
    }

    default void logGunResult(WaveContextFeatures.WaveContext context,
                              double gf,
                              boolean saveObservation) {
        logGunInterval(context, gf, gf, saveObservation);
    }

    default void logGunInterval(WaveContextFeatures.WaveContext context,
                                double gfMin,
                                double gfMax) {
        logGunInterval(context, gfMin, gfMax, true);
    }

    default void logGunInterval(WaveContextFeatures.WaveContext context,
                                double gfMin,
                                double gfMax,
                                boolean saveObservation,
                                boolean updateModel) {
        logGunInterval(context, gfMin, gfMax, saveObservation);
    }

    void logGunInterval(WaveContextFeatures.WaveContext context,
                        double gfMin,
                        double gfMax,
                        boolean saveObservation);

    default void logMovementResult(WaveContextFeatures.WaveContext context,
                                   double gf) {
        logMovementResult(context, gf, true);
    }

    default void logMovementResult(WaveContextFeatures.WaveContext context,
                                   double gf,
                                   boolean saveObservation,
                                   boolean updateModel) {
        logMovementResult(context, gf, saveObservation);
    }

    void logMovementResult(WaveContextFeatures.WaveContext context,
                           double gf,
                           boolean saveObservation);

    GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context);

    GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context);

    default boolean shouldUpdateTargetingModel() {
        return true;
    }

    default boolean shouldUpdateMovementModel() {
        return true;
    }
}
