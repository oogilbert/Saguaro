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

    private static final class PendingPassSample {
        final double[] scores;

        PendingPassSample(double[] scores) {
            this.scores = scores;
        }
    }

    private Info info;
    private final Map<WaveContextFeatures.WaveContext, ShotDodgerExpertSnapshot> movementSnapshotCache =
            createSnapshotCache();
    private final Map<Wave, PendingPassSample> pendingPassSamples = new IdentityHashMap<Wave, PendingPassSample>();
    private final double[] movementScoreSums = new double[ShotDodgerExpertId.VALUES.length];
    private final double[] movementScoreWeights = new double[ShotDodgerExpertId.VALUES.length];

    void setInfo(Info info) {
        this.info = info;
        if (info != null && info.getRobot() != null && info.getRobot().getRoundNum() == 0) {
            Arrays.fill(movementScoreSums, 0.0);
            Arrays.fill(movementScoreWeights, 0.0);
            pendingPassSamples.clear();
        }
        movementSnapshotCache.clear();
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
        ShotDodgerExpertSnapshot snapshot = movementSnapshotForContext(context);
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        ExpertPrediction prediction = snapshot.get(ShotDodgerExpertId.HEAD_ON);
        return prediction != null ? prediction.distribution : null;
    }

    @Override
    public double[] createMovementRecentPerformanceScores(WaveContextFeatures.WaveContext context) {
        return currentMovementScoreSnapshot();
    }

    @Override
    public double[] createMovementRenderGfMarkers(WaveContextFeatures.WaveContext context) {
        ShotDodgerExpertSnapshot snapshot = movementSnapshotForContext(context);
        if (snapshot == null) {
            return null;
        }
        return ShotDodgerExpertTransforms.createRenderGfMarkers(snapshot, null);
    }

    @Override
    public double[] createMovementRenderGfMarkers(Wave wave) {
        if (wave == null) {
            return null;
        }
        ShotDodgerExpertSnapshot snapshot = movementSnapshotForWave(wave);
        if (snapshot == null) {
            return null;
        }
        return ShotDodgerExpertTransforms.createRenderGfMarkers(snapshot, wave);
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
    public void onResolvedEnemyWaveHit(Wave wave,
                                       double gf) {
        double[] exactScores = scoreExactPredictions(centersForWave(wave), gf);
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
        double[] currentScores = currentMovementScoreSnapshot();
        ShotDodgerExpertId bestExpertId = ShotDodgerExpertId.HEAD_ON;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ShotDodgerExpertId expertId : ShotDodgerExpertId.VALUES) {
            double score = currentScores[expertId.ordinal()];
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

    private ShotDodgerExpertSnapshot movementSnapshotForWave(Wave wave) {
        if (wave == null) {
            return null;
        }
        WaveContextFeatures.WaveContext sourceContext =
                wave.sourceTickContext != null ? wave.sourceTickContext : wave.fireTimeContext;
        return movementSnapshotForContext(sourceContext);
    }

    private ShotDodgerExpertSnapshot movementSnapshotForContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        ShotDodgerExpertSnapshot snapshot = movementSnapshotCache.get(context);
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = ShotDodgerSourceExpertCatalog.createMovementSnapshot(context);
        if (snapshot != null) {
            movementSnapshotCache.put(context, snapshot);
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
        ShotDodgerExpertSnapshot snapshot = movementSnapshotForWave(wave);
        return snapshot != null ? ShotDodgerExpertTransforms.createRenderGfMarkers(snapshot, wave) : null;
    }

    private double[] centersForContext(WaveContextFeatures.WaveContext context) {
        ShotDodgerExpertSnapshot snapshot = movementSnapshotForContext(context);
        return snapshot != null ? ShotDodgerExpertTransforms.createRenderGfMarkers(snapshot, null) : null;
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
            if (center >= gfMin && center <= gfMax) {
                scores[i] = 0.0;
            }
        }
        return scores;
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
}
