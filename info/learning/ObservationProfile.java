package oog.mega.saguaro.info.learning;

import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.wave.Wave;
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

    default double[] createGunRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        return null;
    }

    default double[] createMovementRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        return null;
    }

    default void onResolvedGunWave(Wave wave,
                                   double gfMin,
                                   double gfMax) {
    }

    default void onInvalidatedGunWave(Wave wave) {
    }

    default void onResolvedMovementWave(Wave wave,
                                        double gfMin,
                                        double gfMax) {
    }

    default void prepareWaveRenderState(Info info,
                                        List<Wave> enemyWaves,
                                        List<Wave> myWaves) {
    }

    default void onResolvedEnemyWaveHit(WaveContextFeatures.WaveContext context,
                                        double gf) {
    }

    default boolean shouldRefreshEnemyWavesAfterResolvedHit() {
        return shouldUpdateMovementModel();
    }

    default boolean shouldUpdateTargetingModel() {
        return true;
    }

    default boolean shouldUpdateMovementModel() {
        return true;
    }

    default boolean shouldUseVirtualMovementWaves() {
        return true;
    }
}
