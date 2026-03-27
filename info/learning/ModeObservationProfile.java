package oog.mega.saguaro.info.learning;

import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

public final class ModeObservationProfile implements ObservationProfile {
    private ObservationProfile delegate;
    private ModeObservationPolicy policy = ModeObservationPolicy.FULL;

    public ModeObservationProfile(ObservationProfile delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Mode observation profile requires a non-null delegate");
        }
        this.delegate = delegate;
    }

    public void setDelegate(ObservationProfile delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Mode observation profile requires a non-null delegate");
        }
        this.delegate = delegate;
    }

    public void setPolicy(ModeObservationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("Mode observation profile requires a non-null policy");
        }
        this.policy = policy;
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation) {
        if (!policy.logTargetingObservations) {
            return;
        }
        delegate.logGunInterval(
                context,
                gfMin,
                gfMax,
                saveObservation && policy.saveTargetingObservations);
    }

    @Override
    public void logGunInterval(WaveContextFeatures.WaveContext context,
                               double gfMin,
                               double gfMax,
                               boolean saveObservation,
                               boolean updateModel) {
        if (!policy.logTargetingObservations && !updateModel) {
            return;
        }
        delegate.logGunInterval(
                context,
                gfMin,
                gfMax,
                saveObservation && policy.saveTargetingObservations,
                updateModel && policy.updateTargetingModel);
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation) {
        if (!policy.logMovementObservations) {
            return;
        }
        delegate.logMovementResult(
                context,
                gf,
                saveObservation && policy.saveMovementObservations);
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation,
                                  boolean updateModel) {
        if (!policy.logMovementObservations && !updateModel) {
            return;
        }
        delegate.logMovementResult(
                context,
                gf,
                saveObservation && policy.saveMovementObservations,
                updateModel && policy.updateMovementModel);
    }

    @Override
    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        if (!policy.useTargetingDistributions) {
            return null;
        }
        return delegate.createGunDistribution(context);
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        if (!policy.useMovementDistributions) {
            return null;
        }
        return delegate.createMovementDistribution(context);
    }

    @Override
    public double[] createGunRecentPerformanceScores(WaveContextFeatures.WaveContext context) {
        if (!policy.useTargetingDistributions) {
            return null;
        }
        return delegate.createGunRecentPerformanceScores(context);
    }

    @Override
    public double[] createMovementRecentPerformanceScores(WaveContextFeatures.WaveContext context) {
        if (!policy.useMovementDistributions) {
            return null;
        }
        return delegate.createMovementRecentPerformanceScores(context);
    }

    @Override
    public double[] createGunRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        if (!policy.useTargetingDistributions) {
            return null;
        }
        return delegate.createGunRenderGfMarkers(context);
    }

    @Override
    public double[] createMovementRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        if (!policy.useMovementDistributions) {
            return null;
        }
        return delegate.createMovementRenderGfMarkers(context);
    }

    @Override
    public double[] createMovementRenderGfMarkers(Wave wave) {
        if (!policy.useMovementDistributions) {
            return null;
        }
        return delegate.createMovementRenderGfMarkers(wave);
    }

    @Override
    public double[] createGunHistoricalSignaturePoint(WaveContextFeatures.WaveContext context) {
        if (!policy.useTargetingDistributions) {
            return null;
        }
        return delegate.createGunHistoricalSignaturePoint(context);
    }

    @Override
    public double[] createMovementHistoricalSignaturePoint(WaveContextFeatures.WaveContext context) {
        if (!policy.useMovementDistributions) {
            return null;
        }
        return delegate.createMovementHistoricalSignaturePoint(context);
    }

    @Override
    public void onResolvedGunWave(Wave wave,
                                  double gfMin,
                                  double gfMax) {
        delegate.onResolvedGunWave(wave, gfMin, gfMax);
    }

    @Override
    public void onInvalidatedGunWave(Wave wave) {
        delegate.onInvalidatedGunWave(wave);
    }

    @Override
    public void onResolvedMovementWave(Wave wave,
                                       double gfMin,
                                       double gfMax) {
        delegate.onResolvedMovementWave(wave, gfMin, gfMax);
    }

    @Override
    public void onResolvedMovementImpactWave(Wave wave,
                                             double gfMin,
                                             double gfMax) {
        delegate.onResolvedMovementImpactWave(wave, gfMin, gfMax);
    }

    @Override
    public void prepareWaveRenderState(Info info,
                                       List<Wave> enemyWaves,
                                       List<Wave> myWaves) {
        delegate.prepareWaveRenderState(info, enemyWaves, myWaves);
    }

    @Override
    public void onResolvedEnemyWaveHit(WaveContextFeatures.WaveContext context,
                                       double gf) {
        delegate.onResolvedEnemyWaveHit(context, gf);
    }

    @Override
    public void onResolvedEnemyWaveHit(Wave wave,
                                       double gf) {
        delegate.onResolvedEnemyWaveHit(wave, gf);
    }

    @Override
    public boolean shouldRefreshEnemyWavesAfterResolvedHit() {
        return policy.useMovementDistributions && delegate.shouldRefreshEnemyWavesAfterResolvedHit();
    }

    @Override
    public boolean shouldUpdateTargetingModel() {
        return policy.updateTargetingModel;
    }

    @Override
    public boolean shouldUpdateMovementModel() {
        return policy.updateMovementModel;
    }

    @Override
    public boolean shouldUseVirtualMovementWaves() {
        return delegate.shouldUseVirtualMovementWaves();
    }
}
