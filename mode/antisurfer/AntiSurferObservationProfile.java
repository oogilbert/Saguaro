package oog.mega.saguaro.mode.antisurfer;

import java.util.LinkedHashMap;
import java.util.Map;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

final class AntiSurferObservationProfile implements ObservationProfile {
    private static final int SNAPSHOT_CACHE_CAPACITY = 256;

    private Info info;
    private final AntiSurferRecentWeighting recentWeighting = new AntiSurferRecentWeighting();
    private final Map<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot> gunSnapshotCache =
            createSnapshotCache();
    private final Map<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot> movementSnapshotCache =
            createSnapshotCache();

    void setInfo(Info info) {
        this.info = info;
        gunSnapshotCache.clear();
        movementSnapshotCache.clear();
        AntiSurferPreciseMea.clearCache();
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
        ExpertPrediction prediction = ActiveAntiSurferExpert.createEnsemblePrediction(
                gunSnapshotFor(context),
                recentWeighting.createGunWeights(info));
        return prediction != null ? prediction.distribution : null;
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        ExpertPrediction prediction = ActiveAntiSurferExpert.createEnsemblePrediction(
                movementSnapshotFor(context),
                recentWeighting.createMovementWeights());
        return prediction != null ? prediction.distribution : null;
    }

    @Override
    public double[] createGunRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        AntiSurferExpertSnapshot snapshot = gunSnapshotFor(context);
        return snapshot != null ? snapshot.centers() : null;
    }

    @Override
    public double[] createMovementRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        AntiSurferExpertSnapshot snapshot = movementSnapshotFor(context);
        return snapshot != null ? snapshot.centers() : null;
    }

    @Override
    public void onResolvedGunWave(oog.mega.saguaro.info.wave.Wave wave,
                                  double gfMin,
                                  double gfMax) {
        recentWeighting.onResolvedGunWave(wave, gfMin, gfMax);
    }

    @Override
    public void onInvalidatedGunWave(oog.mega.saguaro.info.wave.Wave wave) {
        recentWeighting.onInvalidatedGunWave(wave, info);
    }

    @Override
    public void onResolvedMovementWave(oog.mega.saguaro.info.wave.Wave wave,
                                       double gfMin,
                                       double gfMax) {
        recentWeighting.onResolvedMovementWave(wave, gfMin, gfMax);
    }

    @Override
    public void prepareWaveRenderState(Info info,
                                       java.util.List<oog.mega.saguaro.info.wave.Wave> enemyWaves,
                                       java.util.List<oog.mega.saguaro.info.wave.Wave> myWaves) {
        recentWeighting.prepareWaveRenderState(info, enemyWaves, myWaves);
    }

    @Override
    public boolean shouldUpdateTargetingModel() {
        return false;
    }

    @Override
    public boolean shouldUpdateMovementModel() {
        return false;
    }

    @Override
    public boolean shouldUseVirtualMovementWaves() {
        return false;
    }

    private AntiSurferExpertSnapshot gunSnapshotFor(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        AntiSurferExpertSnapshot snapshot = gunSnapshotCache.get(context);
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = AntiSurferSourceExpertCatalog.createGunAllSnapshot(
                context,
                info != null ? info.getEnemy() : null,
                info);
        if (snapshot != null) {
            gunSnapshotCache.put(context, snapshot);
        }
        return snapshot;
    }

    private AntiSurferExpertSnapshot movementSnapshotFor(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        AntiSurferExpertSnapshot snapshot = movementSnapshotCache.get(context);
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = AntiSurferSourceExpertCatalog.createMovementAllSnapshot(context, info);
        if (snapshot != null) {
            movementSnapshotCache.put(context, snapshot);
        }
        return snapshot;
    }

    private static Map<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot> createSnapshotCache() {
        return new LinkedHashMap<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot> eldest) {
                return size() > SNAPSHOT_CACHE_CAPACITY;
            }
        };
    }
}
