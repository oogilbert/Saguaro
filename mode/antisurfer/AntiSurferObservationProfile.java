package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;

final class AntiSurferObservationProfile implements ObservationProfile {
    private static final int SNAPSHOT_CACHE_CAPACITY = 256;
    private static final double MIN_ACTIVE_WEIGHT = 1e-3;

    private Info info;
    private final AntiSurferHistoricalWeighting historicalWeighting = new AntiSurferHistoricalWeighting();
    private final AntiSurferLearnedRecentWeighting learnedRecentWeighting =
            new AntiSurferLearnedRecentWeighting();
    private final AntiSurferRecentWeighting recentWeighting = new AntiSurferRecentWeighting();
    private final Map<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot> gunSnapshotCache =
            createSnapshotCache();
    private final Map<WaveContextFeatures.WaveContext, AntiSurferExpertSnapshot> movementSnapshotCache =
            createSnapshotCache();

    void setInfo(Info info) {
        this.info = info;
        if (info != null && info.getRobot() != null && info.getRobot().getRoundNum() == 0) {
            historicalWeighting.clear();
            learnedRecentWeighting.clear();
            recentWeighting.clear();
        }
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
        double[] combinedWeights = combineWeights(
                historicalWeighting.createGunWeights(context, info),
                learnedRecentWeighting.createGunFactors(recentWeighting.createGunWeights(info)));
        ExpertPrediction prediction = ActiveAntiSurferExpert.createEnsemblePrediction(
                gunSnapshotFor(context),
                combinedWeights);
        return prediction != null ? prediction.distribution : null;
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        double[] combinedWeights = combineWeights(
                historicalWeighting.createMovementWeights(context, info),
                learnedRecentWeighting.createMovementFactors(recentWeighting.createMovementWeights()));
        return createWeightedMovementDistribution(movementSnapshotFor(context), combinedWeights);
    }

    @Override
    public double[] createGunRecentPerformanceScores(WaveContextFeatures.WaveContext context) {
        return recentWeighting.createGunWeights(info);
    }

    @Override
    public double[] createMovementRecentPerformanceScores(WaveContextFeatures.WaveContext context) {
        return recentWeighting.createMovementWeights();
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
    public double[] createGunHistoricalSignaturePoint(WaveContextFeatures.WaveContext context) {
        return historicalWeighting.captureCurrentGunSignaturePoint();
    }

    @Override
    public double[] createMovementHistoricalSignaturePoint(WaveContextFeatures.WaveContext context) {
        return historicalWeighting.captureCurrentMovementSignaturePoint();
    }

    @Override
    public void onResolvedGunWave(oog.mega.saguaro.info.wave.Wave wave,
                                  double gfMin,
                                  double gfMax) {
        recentWeighting.onResolvedGunWave(wave, gfMin, gfMax);
        historicalWeighting.onResolvedGunWave(wave, gfMin, gfMax);
        learnedRecentWeighting.onResolvedGunWave(wave, gfMin, gfMax);
    }

    @Override
    public void onInvalidatedGunWave(oog.mega.saguaro.info.wave.Wave wave) {
        if (wave == null || info == null || info.getRobot() == null) {
            return;
        }
        double[] reachableInterval = AntiSurferReachability.computeGunWaveReachableGfInterval(
                wave,
                info.getEnemy(),
                info.getRobot().getTime(),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        if (reachableInterval == null) {
            return;
        }
        recentWeighting.onInvalidatedGunWave(wave, info);
        historicalWeighting.onInvalidatedGunWave(wave, reachableInterval[0], reachableInterval[1]);
        learnedRecentWeighting.onInvalidatedGunWave(wave, reachableInterval[0], reachableInterval[1]);
    }

    @Override
    public void onResolvedMovementWave(oog.mega.saguaro.info.wave.Wave wave,
                                       double gfMin,
                                       double gfMax) {
    }

    @Override
    public void onResolvedMovementImpactWave(oog.mega.saguaro.info.wave.Wave wave,
                                             double gfMin,
                                             double gfMax) {
        recentWeighting.onResolvedMovementWave(wave, gfMin, gfMax);
        historicalWeighting.onResolvedMovementWave(wave, gfMin, gfMax);
        learnedRecentWeighting.onResolvedMovementWave(wave, gfMin, gfMax);
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

    ShotSolution selectBestReachableGunShot(WaveContextFeatures.WaveContext context,
                                            double shooterX,
                                            double shooterY,
                                            double targetX,
                                            double targetY,
                                            double firePower,
                                            double gunHeadingAtDecision,
                                            int ticksUntilFire,
                                            GunController gun) {
        if (context == null || gun == null || !(firePower >= 0.1)) {
            return null;
        }
        double[] combinedWeights = combineWeights(
                historicalWeighting.createGunWeights(context, info),
                learnedRecentWeighting.createGunFactors(recentWeighting.createGunWeights(info)));
        AntiSurferExpertSnapshot snapshot = gunSnapshotFor(context);
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }

        double referenceBearing = Math.atan2(targetX - shooterX, targetY - shooterY);
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        ShotSolution bestShot = null;
        double bestWeight = Double.NEGATIVE_INFINITY;
        for (AntiSurferExpertId expertId : AntiSurferExpertId.VALUES) {
            ExpertPrediction prediction = snapshot.get(expertId);
            if (prediction == null) {
                continue;
            }
            double weight = combinedWeights != null
                    && expertId.ordinal() < combinedWeights.length
                    && Double.isFinite(combinedWeights[expertId.ordinal()])
                    ? Math.max(0.0, combinedWeights[expertId.ordinal()])
                    : 1.0;
            if (!(weight > 0.0)) {
                continue;
            }
            double desiredFiringAngle = MathUtils.gfToAngle(referenceBearing, prediction.centerGf, mea);
            ShotSolution shot = gun.evaluateShotAtAngleFromPosition(
                    shooterX,
                    shooterY,
                    targetX,
                    targetY,
                    firePower,
                    desiredFiringAngle,
                    gunHeadingAtDecision,
                    ticksUntilFire);
            if (shot == null || !Double.isFinite(shot.firingAngle)) {
                continue;
            }
            if (weight > bestWeight) {
                bestWeight = weight;
                bestShot = shot;
            }
        }
        return bestShot;
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

    private static GuessFactorDistribution createWeightedMovementDistribution(AntiSurferExpertSnapshot snapshot,
                                                                              double[] weights) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        ArrayList<Double> centers = new ArrayList<Double>();
        ArrayList<Double> activeWeights = new ArrayList<Double>();
        for (AntiSurferExpertId expertId : AntiSurferExpertId.VALUES) {
            ExpertPrediction prediction = snapshot.get(expertId);
            if (prediction == null || !Double.isFinite(prediction.centerGf)) {
                continue;
            }
            double weight = weights != null
                    && expertId.ordinal() < weights.length
                    && Double.isFinite(weights[expertId.ordinal()])
                    ? Math.max(0.0, weights[expertId.ordinal()])
                    : 1.0;
            if (!(weight > 0.0)) {
                continue;
            }
            centers.add(prediction.centerGf);
            activeWeights.add(weight);
        }
        if (centers.isEmpty()) {
            return null;
        }
        double[] centerArray = new double[centers.size()];
        double[] weightArray = new double[activeWeights.size()];
        for (int i = 0; i < centers.size(); i++) {
            centerArray[i] = centers.get(i);
            weightArray[i] = activeWeights.get(i);
        }
        return new KDEDistribution(centerArray, weightArray, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH);
    }

    private static double[] combineWeights(double[] primaryWeights, double[] factorWeights) {
        double[] combined = new double[AntiSurferExpertId.VALUES.length];
        for (int i = 0; i < combined.length; i++) {
            double primary = primaryWeights != null
                    && i < primaryWeights.length
                    && Double.isFinite(primaryWeights[i])
                    ? primaryWeights[i]
                    : 1.0;
            double factor = factorWeights != null
                    && i < factorWeights.length
                    && Double.isFinite(factorWeights[i])
                    ? factorWeights[i]
                    : 1.0;
            combined[i] = Math.max(MIN_ACTIVE_WEIGHT, primary * factor);
        }
        return combined;
    }
}
