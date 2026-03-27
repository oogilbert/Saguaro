package oog.mega.saguaro.mode.shotdodger;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.ScoreMaxLearningProfile;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

final class ShotDodgerObservationProfile implements ObservationProfile {
    private static final int SNAPSHOT_CACHE_CAPACITY = 256;
    private static final double DEFAULT_EXPERT_SCORE = 0.5;
    private static final double EXACT_GUESS_FACTOR_SIGMA = 0.18;
    private static final double PASS_INTERVAL_SIGMA = 0.22;

    private static final class PendingPassSample {
        final double[] scores;

        PendingPassSample(double[] scores) {
            this.scores = scores;
        }
    }

    private Info info;
    private final Map<WaveContextFeatures.WaveContext, ShotDodgerExpertSnapshot> movementSnapshotCache =
            createSnapshotCache();
    private final Map<WaveContextFeatures.WaveContext, double[]> movementCentersByContext =
            createCenterCache();
    private final Map<Wave, PendingPassSample> pendingPassSamples = new IdentityHashMap<Wave, PendingPassSample>();
    private final double[] movementScoreSums = new double[ShotDodgerExpertId.VALUES.length];
    private final double[] movementScoreWeights = new double[ShotDodgerExpertId.VALUES.length];

    void setInfo(Info info) {
        this.info = info;
        if (info != null && info.getRobot() != null && info.getRobot().getRoundNum() == 0) {
            Arrays.fill(movementScoreSums, 0.0);
            Arrays.fill(movementScoreWeights, 0.0);
            pendingPassSamples.clear();
            movementCentersByContext.clear();
        }
        movementSnapshotCache.clear();
        ShotDodgerPreciseMea.clearCache();
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
    }

    @Override
    public void logMovementResult(WaveContextFeatures.WaveContext context,
                                  double gf,
                                  boolean saveObservation,
                                  boolean updateModel) {
    }

    @Override
    public GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return ScoreMaxLearningProfile.INSTANCE.createGunDistribution(context);
    }

    @Override
    public GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        ShotDodgerExpertSnapshot snapshot = movementSnapshotFor(context);
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        ExpertPrediction prediction = snapshot.get(currentBestMovementExpertId());
        if (prediction == null || prediction.distribution == null) {
            for (ExpertPrediction activePrediction : snapshot.activePredictions()) {
                if (activePrediction != null && activePrediction.distribution != null) {
                    return activePrediction.distribution;
                }
            }
            return null;
        }
        return prediction.distribution;
    }

    @Override
    public double[] createMovementRecentPerformanceScores(WaveContextFeatures.WaveContext context) {
        return currentMovementScoreSnapshot();
    }

    @Override
    public double[] createMovementRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        ShotDodgerExpertSnapshot snapshot = movementSnapshotFor(context);
        if (snapshot == null) {
            return null;
        }
        double[] centers = snapshot.centers();
        if (context != null) {
            movementCentersByContext.put(context, centers.clone());
        }
        return centers;
    }

    @Override
    public void onResolvedMovementWave(Wave wave,
                                       double gfMin,
                                       double gfMax) {
        double[] centers = centersForWave(wave);
        double[] passScores = scorePassPredictions(centers, gfMin, gfMax);
        if (passScores == null) {
            return;
        }
        PendingPassSample previous = pendingPassSamples.put(wave, new PendingPassSample(passScores));
        if (previous != null) {
            removeMovementSample(previous.scores);
        }
        applyMovementSample(passScores);
    }

    @Override
    public void onResolvedMovementImpactWave(Wave wave,
                                             double gfMin,
                                             double gfMax) {
        PendingPassSample pending = pendingPassSamples.remove(wave);
        if (pending != null) {
            removeMovementSample(pending.scores);
        }
    }

    @Override
    public void onResolvedEnemyWaveHit(WaveContextFeatures.WaveContext context,
                                       double gf) {
        double[] exactScores = scoreExactPredictions(centersForContext(context), gf);
        applyMovementSample(exactScores);
    }

    @Override
    public void prepareWaveRenderState(Info info,
                                       List<Wave> enemyWaves,
                                       List<Wave> myWaves) {
        if (enemyWaves == null) {
            return;
        }
        double[] currentScores = currentMovementScoreSnapshot();
        for (Wave wave : enemyWaves) {
            if (wave == null || wave.fireTimeContext == null) {
                continue;
            }
            wave.fireTimeRecentExpertScores = currentScores.clone();
        }
    }

    @Override
    public boolean shouldRefreshEnemyWavesAfterResolvedHit() {
        return true;
    }

    @Override
    public boolean shouldUpdateTargetingModel() {
        return true;
    }

    @Override
    public boolean shouldUpdateMovementModel() {
        return false;
    }

    @Override
    public boolean shouldUseVirtualMovementWaves() {
        return false;
    }

    ShotDodgerExpertId currentBestMovementExpertId() {
        ShotDodgerExpertId bestExpertId = ShotDodgerExpertId.COSTANZA;
        double bestScore = Double.NEGATIVE_INFINITY;
        double[] scores = currentMovementScoreSnapshot();
        for (ShotDodgerExpertId expertId : ShotDodgerExpertId.VALUES) {
            double score = scores[expertId.ordinal()];
            if (!Double.isFinite(score)) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestExpertId = expertId;
            }
        }
        return bestExpertId;
    }

    private ShotDodgerExpertSnapshot movementSnapshotFor(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        ShotDodgerExpertSnapshot snapshot = movementSnapshotCache.get(context);
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = ShotDodgerSourceExpertCatalog.createMovementAllSnapshot(context, info);
        if (snapshot != null) {
            movementSnapshotCache.put(context, snapshot);
            movementCentersByContext.put(context, snapshot.centers());
        }
        return snapshot;
    }

    private double[] centersForWave(Wave wave) {
        if (wave == null) {
            return null;
        }
        if (wave.fireTimeRenderGfMarkers != null) {
            return wave.fireTimeRenderGfMarkers;
        }
        return centersForContext(wave.fireTimeContext);
    }

    private double[] centersForContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        double[] centers = movementCentersByContext.get(context);
        if (centers != null) {
            return centers;
        }
        ShotDodgerExpertSnapshot snapshot = movementSnapshotFor(context);
        return snapshot != null ? snapshot.centers() : null;
    }

    private void applyMovementSample(double[] sampleScores) {
        if (sampleScores == null) {
            return;
        }
        for (int i = 0; i < sampleScores.length && i < movementScoreSums.length; i++) {
            double score = sampleScores[i];
            if (!Double.isFinite(score)) {
                continue;
            }
            movementScoreSums[i] += score;
            movementScoreWeights[i] += 1.0;
        }
    }

    private void removeMovementSample(double[] sampleScores) {
        if (sampleScores == null) {
            return;
        }
        for (int i = 0; i < sampleScores.length && i < movementScoreSums.length; i++) {
            double score = sampleScores[i];
            if (!Double.isFinite(score)) {
                continue;
            }
            movementScoreSums[i] -= score;
            movementScoreWeights[i] = Math.max(0.0, movementScoreWeights[i] - 1.0);
        }
    }

    private double[] currentMovementScoreSnapshot() {
        double[] snapshot = new double[ShotDodgerExpertId.VALUES.length];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = movementScoreWeights[i] > 0.0
                    ? movementScoreSums[i] / movementScoreWeights[i]
                    : DEFAULT_EXPERT_SCORE;
        }
        return snapshot;
    }

    private static double[] scoreExactPredictions(double[] centers, double resolvedGf) {
        if (centers == null) {
            return null;
        }
        double[] scores = new double[ShotDodgerExpertId.VALUES.length];
        Arrays.fill(scores, Double.NaN);
        for (int i = 0; i < scores.length && i < centers.length; i++) {
            double center = centers[i];
            if (!Double.isFinite(center)) {
                continue;
            }
            double distance = Math.abs(center - resolvedGf);
            scores[i] = gaussianScore(distance, EXACT_GUESS_FACTOR_SIGMA);
        }
        return scores;
    }

    private static double[] scorePassPredictions(double[] centers, double gfMin, double gfMax) {
        if (centers == null) {
            return null;
        }
        double[] scores = new double[ShotDodgerExpertId.VALUES.length];
        Arrays.fill(scores, Double.NaN);
        for (int i = 0; i < scores.length && i < centers.length; i++) {
            double center = centers[i];
            if (!Double.isFinite(center)) {
                continue;
            }
            double distance = intervalDistance(center, gfMin, gfMax);
            scores[i] = 1.0 - gaussianScore(distance, PASS_INTERVAL_SIGMA);
        }
        return scores;
    }

    private static double intervalDistance(double value, double minValue, double maxValue) {
        if (value >= minValue && value <= maxValue) {
            return 0.0;
        }
        return value < minValue ? (minValue - value) : (value - maxValue);
    }

    private static double gaussianScore(double distance, double sigma) {
        if (!(sigma > 0.0)) {
            return distance <= 0.0 ? 1.0 : 0.0;
        }
        double normalized = distance / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private static Map<WaveContextFeatures.WaveContext, ShotDodgerExpertSnapshot> createSnapshotCache() {
        return new LinkedHashMap<WaveContextFeatures.WaveContext, ShotDodgerExpertSnapshot>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<WaveContextFeatures.WaveContext, ShotDodgerExpertSnapshot> eldest) {
                return size() > SNAPSHOT_CACHE_CAPACITY;
            }
        };
    }

    private static Map<WaveContextFeatures.WaveContext, double[]> createCenterCache() {
        return new LinkedHashMap<WaveContextFeatures.WaveContext, double[]>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<WaveContextFeatures.WaveContext, double[]> eldest) {
                return size() > SNAPSHOT_CACHE_CAPACITY;
            }
        };
    }
}
