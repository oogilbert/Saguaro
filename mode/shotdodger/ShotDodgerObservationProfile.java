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
import oog.mega.saguaro.math.MathUtils;

final class ShotDodgerObservationProfile implements ObservationProfile {
    private static final int SNAPSHOT_CACHE_CAPACITY = 256;
    private static final double DEFAULT_EXPERT_SCORE = 0.5;
    private static final double EXACT_GUESS_FACTOR_SIGMA = 0.18;
    private static final double DIVISOR_SAMPLE_EPSILON = 1e-4;
    private static final double MIN_LEARNED_DIVISOR = 4.0;
    private static final double MAX_LEARNED_DIVISOR = 30.0;

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
    private double linearConstantDivisorSum;
    private double linearConstantDivisorWeight;
    private double linearConstantDivisorNoAdjustSum;
    private double linearConstantDivisorNoAdjustWeight;

    void setInfo(Info info) {
        this.info = info;
        if (info != null && info.getRobot() != null && info.getRobot().getRoundNum() == 0) {
            Arrays.fill(movementScoreSums, 0.0);
            Arrays.fill(movementScoreWeights, 0.0);
            pendingPassSamples.clear();
            linearConstantDivisorSum = 0.0;
            linearConstantDivisorWeight = 0.0;
            linearConstantDivisorNoAdjustSum = 0.0;
            linearConstantDivisorNoAdjustWeight = 0.0;
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
        ShotDodgerExpertId activeExpertId = currentBestMovementExpertId();
        ShotDodgerExpertId sourceExpertId =
                activeExpertId.sourceExpertId() != null ? activeExpertId.sourceExpertId() : activeExpertId;
        ExpertPrediction prediction = snapshot.get(sourceExpertId);
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
        if (updateLearnedConstantDivisors(wave, gf)) {
            movementSnapshotCache.clear();
        }
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
        snapshot = ShotDodgerSourceExpertCatalog.createMovementSnapshot(
                context,
                currentLearnedDivisor(linearConstantDivisorSum, linearConstantDivisorWeight, context),
                currentLearnedDivisor(linearConstantDivisorNoAdjustSum, linearConstantDivisorNoAdjustWeight, context));
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

    private boolean updateLearnedConstantDivisors(Wave wave,
                                                  double resolvedGf) {
        if (wave == null || wave.sourceTickContext == null || !Double.isFinite(resolvedGf)) {
            return false;
        }
        double fireReferenceBearing = fireReferenceBearing(wave);
        double fireMea = fireMea(wave);
        if (!Double.isFinite(fireReferenceBearing) || !Double.isFinite(fireMea) || fireMea <= 0.0) {
            return false;
        }
        double actualFireAngle = fireReferenceBearing + resolvedGf * fireMea;
        boolean updated = false;
        updated |= accumulateConstantDivisorSample(wave.sourceTickContext, actualFireAngle, false);
        updated |= accumulateConstantDivisorSample(wave.sourceTickContext, actualFireAngle, true, wave.fireTimeShooterBodyTurn);
        return updated;
    }

    private boolean accumulateConstantDivisorSample(WaveContextFeatures.WaveContext sourceContext,
                                                    double actualFireAngle,
                                                    boolean subtractBodyTurn) {
        return accumulateConstantDivisorSample(sourceContext, actualFireAngle, subtractBodyTurn, Double.NaN);
    }

    private boolean accumulateConstantDivisorSample(WaveContextFeatures.WaveContext sourceContext,
                                                    double actualFireAngle,
                                                    boolean subtractBodyTurn,
                                                    double bodyTurn) {
        if (sourceContext == null || !Double.isFinite(actualFireAngle)) {
            return false;
        }
        double sourceReferenceBearing = Math.atan2(
                sourceContext.targetX - sourceContext.sourceX,
                sourceContext.targetY - sourceContext.sourceY);
        double sourceAngle = subtractBodyTurn && Double.isFinite(bodyTurn)
                ? sourceAngleWithoutBodyTurn(actualFireAngle, bodyTurn)
                : actualFireAngle;
        double angleOffset = normalizeAngle(sourceAngle - sourceReferenceBearing);
        double lateralComponent =
                sourceContext.targetVelocity * Math.sin(sourceContext.targetHeading - sourceReferenceBearing);
        if (Math.abs(angleOffset) < DIVISOR_SAMPLE_EPSILON
                || Math.abs(lateralComponent) < DIVISOR_SAMPLE_EPSILON) {
            return false;
        }
        double divisor = lateralComponent / angleOffset;
        if (!Double.isFinite(divisor) || divisor < MIN_LEARNED_DIVISOR || divisor > MAX_LEARNED_DIVISOR) {
            return false;
        }
        if (subtractBodyTurn) {
            linearConstantDivisorNoAdjustSum += divisor;
            linearConstantDivisorNoAdjustWeight += 1.0;
        } else {
            linearConstantDivisorSum += divisor;
            linearConstantDivisorWeight += 1.0;
        }
        return true;
    }

    private static double currentLearnedDivisor(double divisorSum,
                                                double divisorWeight,
                                                WaveContextFeatures.WaveContext context) {
        if (divisorWeight > 0.0) {
            return divisorSum / divisorWeight;
        }
        return context != null ? context.bulletSpeed : Double.NaN;
    }

    private static double fireReferenceBearing(Wave wave) {
        if (wave == null) {
            return Double.NaN;
        }
        if (Double.isFinite(wave.targetX)
                && Double.isFinite(wave.targetY)
                && Double.isFinite(wave.originX)
                && Double.isFinite(wave.originY)) {
            return Math.atan2(wave.targetX - wave.originX, wave.targetY - wave.originY);
        }
        if (wave.fireTimeContext == null) {
            return Double.NaN;
        }
        return Math.atan2(
                wave.fireTimeContext.targetX - wave.fireTimeContext.sourceX,
                wave.fireTimeContext.targetY - wave.fireTimeContext.sourceY);
    }

    private static double fireMea(Wave wave) {
        if (wave == null) {
            return Double.NaN;
        }
        if (Double.isFinite(wave.speed) && wave.speed > 0.0) {
            return MathUtils.maxEscapeAngle(wave.speed);
        }
        if (wave.fireTimeContext == null || !Double.isFinite(wave.fireTimeContext.bulletSpeed)) {
            return Double.NaN;
        }
        return MathUtils.maxEscapeAngle(wave.fireTimeContext.bulletSpeed);
    }

    private static double sourceAngleWithoutBodyTurn(double actualFireAngle,
                                                     double bodyTurn) {
        return normalizeAngle(actualFireAngle - bodyTurn);
    }

    private static double normalizeAngle(double angle) {
        return MathUtils.normalizeAngle(angle);
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
