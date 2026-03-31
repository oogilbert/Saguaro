package oog.mega.saguaro.mode.shotdodger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.learning.ScoreMaxLearningProfile;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;

public final class ShotDodgerObservationProfile implements ObservationProfile {
    private static final int SNAPSHOT_CACHE_CAPACITY = 256;
    private static final double DEFAULT_EXPERT_SCORE = 0.5;
    private static final double EXACT_GUESS_FACTOR_SIGMA = 0.18;
    private static final double DIVISOR_SAMPLE_EPSILON = 1e-4;
    private static final double MIN_LEARNED_DIVISOR = 4.0;
    private static final double MAX_LEARNED_DIVISOR = 30.0;
    private static final int MAX_AVERAGED_LINEAR_CONSTANT = 100;
    private static final double CONSTANT_DIVISOR_ROLLING_DECAY = 0.85;
    private static final int PERSISTED_BOOTSTRAP_SECTION_VERSION = 1;
    private static final int PERSISTED_BOOTSTRAP_BYTES = 9;

    private static ShotDodgerObservationProfile activeInstance;
    private static boolean persistedBootstrapLoaded;
    private static boolean currentBattleDataAvailable;
    private static int persistedBestExpertOrdinal;
    private static double persistedLearnedConstant;

    private static final class PendingPassSample {
        final double[] expertScores;
        final double[] averagedLinearCandidateScores;
        final double[] averagedLinearNoAdjustCandidateScores;

        PendingPassSample(double[] expertScores,
                          double[] averagedLinearCandidateScores,
                          double[] averagedLinearNoAdjustCandidateScores) {
            this.expertScores = expertScores;
            this.averagedLinearCandidateScores = averagedLinearCandidateScores;
            this.averagedLinearNoAdjustCandidateScores = averagedLinearNoAdjustCandidateScores;
        }
    }

    private Info info;
    private final Map<WaveContextFeatures.WaveContext, ShotDodgerExpertSnapshot> movementSnapshotCache =
            createSnapshotCache();
    private final Map<Wave, PendingPassSample> pendingPassSamples = new IdentityHashMap<Wave, PendingPassSample>();
    private final double[] movementScoreSums = new double[ShotDodgerExpertId.VALUES.length];
    private final double[] movementScoreWeights = new double[ShotDodgerExpertId.VALUES.length];
    private final double[] averagedLinearCandidateValues = new double[MAX_AVERAGED_LINEAR_CONSTANT + 1];
    private final double[] averagedLinearCandidateScoreSums = new double[MAX_AVERAGED_LINEAR_CONSTANT + 1];
    private final double[] averagedLinearCandidateScoreWeights = new double[MAX_AVERAGED_LINEAR_CONSTANT + 1];
    private final double[] averagedLinearNoAdjustCandidateScoreSums = new double[MAX_AVERAGED_LINEAR_CONSTANT + 1];
    private final double[] averagedLinearNoAdjustCandidateScoreWeights = new double[MAX_AVERAGED_LINEAR_CONSTANT + 1];
    private long lastAveragedLinearCandidateUpdateTime = Long.MIN_VALUE;
    private double linearConstantDivisorSum;
    private double linearConstantDivisorWeight;
    private double linearConstantDivisorNoAdjustSum;
    private double linearConstantDivisorNoAdjustWeight;

    void setInfo(Info info) {
        this.info = info;
        activeInstance = this;
        if (info != null && info.getRobot() != null && info.getRobot().getRoundNum() == 0) {
            Arrays.fill(movementScoreSums, 0.0);
            Arrays.fill(movementScoreWeights, 0.0);
            Arrays.fill(averagedLinearCandidateScoreSums, 0.0);
            Arrays.fill(averagedLinearCandidateScoreWeights, 0.0);
            Arrays.fill(averagedLinearNoAdjustCandidateScoreSums, 0.0);
            Arrays.fill(averagedLinearNoAdjustCandidateScoreWeights, 0.0);
            linearConstantDivisorSum = 0.0;
            linearConstantDivisorWeight = 0.0;
            linearConstantDivisorNoAdjustSum = 0.0;
            linearConstantDivisorNoAdjustWeight = 0.0;
            if (persistedBootstrapLoaded) {
                movementScoreSums[persistedBestExpertOrdinal] = 1.0;
                movementScoreWeights[persistedBestExpertOrdinal] = 1.0;
                applyPersistedLearnedConstant();
            }
        }
        pendingPassSamples.clear();
        Arrays.fill(averagedLinearCandidateValues, 0.0);
        lastAveragedLinearCandidateUpdateTime = Long.MIN_VALUE;
        movementSnapshotCache.clear();
    }

    void onScannedRobot(EnemyInfo enemy) {
        if (enemy == null) {
            return;
        }
        long sampleTime = enemy.getSourceTickRobotLateralVelocityTime();
        double lateralVelocity = enemy.getSourceTickRobotLateralVelocity();
        updateAveragedLinearCandidates(sampleTime, lateralVelocity);
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
        double[] expertPassScores = scorePassPredictions(centers, gfMin, gfMax);
        double[] averagedLinearPassScores = scorePassPredictions(wave != null ? wave.averagedLinearCandidateGfs : null, gfMin, gfMax);
        double[] averagedLinearNoAdjustPassScores =
                scorePassPredictions(wave != null ? wave.averagedLinearNoAdjustCandidateGfs : null, gfMin, gfMax);
        if (expertPassScores == null
                && averagedLinearPassScores == null
                && averagedLinearNoAdjustPassScores == null) {
            return;
        }
        PendingPassSample previous = pendingPassSamples.put(
                wave,
                new PendingPassSample(expertPassScores, averagedLinearPassScores, averagedLinearNoAdjustPassScores));
        if (previous != null) {
            removeMovementSample(previous.expertScores);
            removeCandidateSample(previous.averagedLinearCandidateScores, averagedLinearCandidateScoreSums, averagedLinearCandidateScoreWeights);
            removeCandidateSample(
                    previous.averagedLinearNoAdjustCandidateScores,
                    averagedLinearNoAdjustCandidateScoreSums,
                    averagedLinearNoAdjustCandidateScoreWeights);
        }
        applyMovementSample(expertPassScores);
        applyCandidateSample(averagedLinearPassScores, averagedLinearCandidateScoreSums, averagedLinearCandidateScoreWeights);
        applyCandidateSample(
                averagedLinearNoAdjustPassScores,
                averagedLinearNoAdjustCandidateScoreSums,
                averagedLinearNoAdjustCandidateScoreWeights);
    }

    @Override
    public void onResolvedMovementImpactWave(Wave wave,
                                             double gfMin,
                                             double gfMax) {
        PendingPassSample pending = pendingPassSamples.remove(wave);
        if (pending != null) {
            removeMovementSample(pending.expertScores);
            removeCandidateSample(pending.averagedLinearCandidateScores, averagedLinearCandidateScoreSums, averagedLinearCandidateScoreWeights);
            removeCandidateSample(
                    pending.averagedLinearNoAdjustCandidateScores,
                    averagedLinearNoAdjustCandidateScoreSums,
                    averagedLinearNoAdjustCandidateScoreWeights);
        }
    }

    @Override
    public void onResolvedEnemyWaveHit(Wave wave,
                                       double gf) {
        if (persistedBootstrapLoaded && !currentBattleDataAvailable) {
            Arrays.fill(movementScoreSums, 0.0);
            Arrays.fill(movementScoreWeights, 0.0);
            Arrays.fill(averagedLinearCandidateScoreSums, 0.0);
            Arrays.fill(averagedLinearCandidateScoreWeights, 0.0);
            Arrays.fill(averagedLinearNoAdjustCandidateScoreSums, 0.0);
            Arrays.fill(averagedLinearNoAdjustCandidateScoreWeights, 0.0);
            linearConstantDivisorSum = 0.0;
            linearConstantDivisorWeight = 0.0;
            linearConstantDivisorNoAdjustSum = 0.0;
            linearConstantDivisorNoAdjustWeight = 0.0;
            movementSnapshotCache.clear();
        }
        currentBattleDataAvailable = true;
        double[] exactScores = scoreExactPredictions(centersForWave(wave), gf);
        applyMovementSample(exactScores);
        boolean updated = false;
        updated |= updateAveragedLinearCandidateScores(wave, gf);
        updated |= updateLearnedConstantDivisors(wave, gf);
        if (updated) {
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
        return false;
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
        ensureAveragedLinearCandidateGfs(wave, sourceContext);
        return createMovementSnapshot(sourceContext);
    }

    private ShotDodgerExpertSnapshot movementSnapshotForContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        ShotDodgerExpertSnapshot snapshot = movementSnapshotCache.get(context);
        if (snapshot != null) {
            return snapshot;
        }
        snapshot = createMovementSnapshot(context);
        if (snapshot != null) {
            movementSnapshotCache.put(context, snapshot);
        }
        return snapshot;
    }

    private ShotDodgerExpertSnapshot createMovementSnapshot(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        return ShotDodgerSourceExpertCatalog.createMovementSnapshot(
                context,
                currentBestAveragedLinearLateralVelocity(false),
                currentBestAveragedLinearLateralVelocity(true),
                currentLearnedDivisor(linearConstantDivisorSum, linearConstantDivisorWeight, context),
                currentLearnedDivisor(linearConstantDivisorNoAdjustSum, linearConstantDivisorNoAdjustWeight, context));
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
        double[] scores = new double[centers.length];
        Arrays.fill(scores, Double.NaN);
        for (int i = 0; i < scores.length; i++) {
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
        double[] scores = new double[centers.length];
        Arrays.fill(scores, Double.NaN);
        for (int i = 0; i < scores.length; i++) {
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

    private boolean updateAveragedLinearCandidateScores(Wave wave,
                                                        double resolvedGf) {
        if (wave == null || !Double.isFinite(resolvedGf)) {
            return false;
        }
        boolean updated = applyAveragedLinearCandidateExactScores(
                wave.averagedLinearCandidateGfs,
                resolvedGf,
                averagedLinearCandidateScoreSums,
                averagedLinearCandidateScoreWeights);
        updated |= applyAveragedLinearCandidateExactScores(
                wave.averagedLinearNoAdjustCandidateGfs,
                resolvedGf,
                averagedLinearNoAdjustCandidateScoreSums,
                averagedLinearNoAdjustCandidateScoreWeights);
        return updated;
    }

    private static boolean applyAveragedLinearCandidateExactScores(double[] candidateGfs,
                                                                   double resolvedGf,
                                                                   double[] scoreSums,
                                                                   double[] scoreWeights) {
        if (candidateGfs == null || scoreSums == null || scoreWeights == null) {
            return false;
        }
        boolean updated = false;
        for (int constant = 1; constant < candidateGfs.length && constant < scoreSums.length; constant++) {
            double predictedGf = candidateGfs[constant];
            if (!Double.isFinite(predictedGf)) {
                continue;
            }
            double distance = Math.abs(predictedGf - resolvedGf);
            scoreSums[constant] += gaussianScore(distance, EXACT_GUESS_FACTOR_SIGMA);
            scoreWeights[constant] += 1.0;
            updated = true;
        }
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
            linearConstantDivisorNoAdjustSum = linearConstantDivisorNoAdjustSum * CONSTANT_DIVISOR_ROLLING_DECAY + divisor;
            linearConstantDivisorNoAdjustWeight = linearConstantDivisorNoAdjustWeight * CONSTANT_DIVISOR_ROLLING_DECAY + 1.0;
        } else {
            linearConstantDivisorSum = linearConstantDivisorSum * CONSTANT_DIVISOR_ROLLING_DECAY + divisor;
            linearConstantDivisorWeight = linearConstantDivisorWeight * CONSTANT_DIVISOR_ROLLING_DECAY + 1.0;
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

    private void ensureAveragedLinearCandidateGfs(Wave wave,
                                                  WaveContextFeatures.WaveContext sourceContext) {
        if (wave == null || sourceContext == null) {
            return;
        }
        if (wave.averagedLinearCandidateGfs == null) {
            wave.averagedLinearCandidateGfs = createAveragedLinearCandidateGfs(wave, sourceContext, false, wave.fireTimeShooterBodyTurn);
        }
        if (wave.averagedLinearNoAdjustCandidateGfs == null) {
            wave.averagedLinearNoAdjustCandidateGfs =
                    createAveragedLinearCandidateGfs(wave, sourceContext, true, wave.fireTimeShooterBodyTurn);
        }
    }

    private double[] createAveragedLinearCandidateGfs(Wave wave,
                                                      WaveContextFeatures.WaveContext sourceContext,
                                                      boolean applyBodyTurn,
                                                      double bodyTurn) {
        if (wave == null || sourceContext == null) {
            return null;
        }
        double sourceReferenceBearing = Math.atan2(
                sourceContext.targetX - sourceContext.sourceX,
                sourceContext.targetY - sourceContext.sourceY);
        double fireReferenceBearing = fireReferenceBearing(wave);
        double fireMea = fireMea(wave);
        if (!Double.isFinite(sourceReferenceBearing)
                || !Double.isFinite(fireReferenceBearing)
                || !Double.isFinite(fireMea)
                || fireMea <= 0.0) {
            return null;
        }
        double[] candidateGfs = new double[MAX_AVERAGED_LINEAR_CONSTANT + 1];
        Arrays.fill(candidateGfs, Double.NaN);
        for (int constant = 1; constant <= MAX_AVERAGED_LINEAR_CONSTANT; constant++) {
            double sourceAngle = sourceReferenceBearing + averagedLinearCandidateValues[constant] / sourceContext.bulletSpeed;
            if (applyBodyTurn) {
                if (!Double.isFinite(bodyTurn)) {
                    continue;
                }
                sourceAngle = normalizeAngle(sourceAngle + bodyTurn);
            }
            candidateGfs[constant] = Math.max(
                    -1.0,
                    Math.min(1.0, MathUtils.angleToGf(fireReferenceBearing, sourceAngle, fireMea)));
        }
        return candidateGfs;
    }

    private double currentBestAveragedLinearLateralVelocity(boolean noAdjust) {
        return averagedLinearCandidateValues[currentBestAveragedLinearConstant(noAdjust)];
    }

    private int currentBestAveragedLinearConstant(boolean noAdjust) {
        double[] scoreSums = noAdjust ? averagedLinearNoAdjustCandidateScoreSums : averagedLinearCandidateScoreSums;
        double[] scoreWeights = noAdjust ? averagedLinearNoAdjustCandidateScoreWeights : averagedLinearCandidateScoreWeights;
        int bestConstant = 1;
        double bestScore = candidateAverageScore(scoreSums, scoreWeights, bestConstant);
        for (int constant = 2; constant <= MAX_AVERAGED_LINEAR_CONSTANT; constant++) {
            double score = candidateAverageScore(scoreSums, scoreWeights, constant);
            if (score > bestScore + 1e-12) {
                bestScore = score;
                bestConstant = constant;
            }
        }
        return bestConstant;
    }

    private static double candidateAverageScore(double[] scoreSums,
                                                double[] scoreWeights,
                                                int constant) {
        if (scoreSums == null || scoreWeights == null || constant < 0 || constant >= scoreSums.length) {
            return DEFAULT_EXPERT_SCORE;
        }
        return scoreWeights[constant] > 0.0
                ? scoreSums[constant] / scoreWeights[constant]
                : DEFAULT_EXPERT_SCORE;
    }

    private void updateAveragedLinearCandidates(long sampleTime,
                                                double lateralVelocity) {
        if (sampleTime == Long.MIN_VALUE
                || !Double.isFinite(lateralVelocity)
                || sampleTime <= lastAveragedLinearCandidateUpdateTime) {
            return;
        }
        for (int constant = 1; constant <= MAX_AVERAGED_LINEAR_CONSTANT; constant++) {
            averagedLinearCandidateValues[constant] =
                    (averagedLinearCandidateValues[constant] * constant + lateralVelocity) / (constant + 1.0);
        }
        lastAveragedLinearCandidateUpdateTime = sampleTime;
        movementSnapshotCache.clear();
    }

    private void applyCandidateSample(double[] sampleScores,
                                      double[] scoreSums,
                                      double[] scoreWeights) {
        if (sampleScores == null || scoreSums == null || scoreWeights == null) {
            return;
        }
        for (int constant = 1; constant < sampleScores.length && constant < scoreSums.length; constant++) {
            double score = sampleScores[constant];
            if (!Double.isFinite(score)) {
                continue;
            }
            scoreSums[constant] += score;
            scoreWeights[constant] += 1.0;
        }
    }

    private void removeCandidateSample(double[] sampleScores,
                                       double[] scoreSums,
                                       double[] scoreWeights) {
        if (sampleScores == null || scoreSums == null || scoreWeights == null) {
            return;
        }
        for (int constant = 1; constant < sampleScores.length && constant < scoreSums.length; constant++) {
            double score = sampleScores[constant];
            if (!Double.isFinite(score)) {
                continue;
            }
            scoreSums[constant] -= score;
            scoreWeights[constant] = Math.max(0.0, scoreWeights[constant] - 1.0);
        }
    }

    private static double normalizeAngle(double angle) {
        return MathUtils.normalizeAngle(angle);
    }

    private void applyPersistedLearnedConstant() {
        ShotDodgerExpertId expertId = ShotDodgerExpertId.VALUES[persistedBestExpertOrdinal];
        double constant = persistedLearnedConstant;
        switch (expertId) {
            case AVERAGED_LINEAR:
                if (Double.isFinite(constant)) {
                    int idx = (int) constant;
                    if (idx >= 1 && idx <= MAX_AVERAGED_LINEAR_CONSTANT) {
                        averagedLinearCandidateScoreSums[idx] = 1.0;
                        averagedLinearCandidateScoreWeights[idx] = 1.0;
                    }
                }
                break;
            case AVERAGED_LINEAR_NO_GUN_ADJUST:
                if (Double.isFinite(constant)) {
                    int idx = (int) constant;
                    if (idx >= 1 && idx <= MAX_AVERAGED_LINEAR_CONSTANT) {
                        averagedLinearNoAdjustCandidateScoreSums[idx] = 1.0;
                        averagedLinearNoAdjustCandidateScoreWeights[idx] = 1.0;
                    }
                }
                break;
            case LINEAR_CONSTANT_DIVISOR:
                if (Double.isFinite(constant)) {
                    linearConstantDivisorSum = constant;
                    linearConstantDivisorWeight = 1.0;
                }
                break;
            case LINEAR_CONSTANT_DIVISOR_NO_GUN_ADJUST:
                if (Double.isFinite(constant)) {
                    linearConstantDivisorNoAdjustSum = constant;
                    linearConstantDivisorNoAdjustWeight = 1.0;
                }
                break;
            default:
                break;
        }
    }

    static void startBattlePersistence() {
        activeInstance = null;
        persistedBootstrapLoaded = false;
        currentBattleDataAvailable = false;
        persistedBestExpertOrdinal = 0;
        persistedLearnedConstant = Double.NaN;
    }

    static void loadPersistedBootstrap(int sectionVersion, byte[] payload) {
        if (payload == null) {
            return;
        }
        if (sectionVersion != PERSISTED_BOOTSTRAP_SECTION_VERSION) {
            throw new IllegalStateException(
                    "Unsupported shotdodger-bootstrap section version " + sectionVersion);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (payload.length != PERSISTED_BOOTSTRAP_BYTES) {
                throw new IllegalStateException("Unexpected shotdodger-bootstrap payload length");
            }
            int bestExpertOrdinal = in.readUnsignedByte();
            if (bestExpertOrdinal < 0 || bestExpertOrdinal >= ShotDodgerExpertId.VALUES.length) {
                throw new IllegalStateException(
                        "Invalid shotdodger-bootstrap expert ordinal " + bestExpertOrdinal);
            }
            double learnedConstant = in.readDouble();
            if (!Double.isNaN(learnedConstant) && !Double.isFinite(learnedConstant)) {
                throw new IllegalStateException(
                        "shotdodger-bootstrap learned constant must be finite or NaN");
            }
            if (in.available() != 0) {
                throw new IllegalStateException(
                        "shotdodger-bootstrap payload contained trailing bytes");
            }
            persistedBootstrapLoaded = true;
            persistedBestExpertOrdinal = bestExpertOrdinal;
            persistedLearnedConstant = learnedConstant;
            if (activeInstance != null) {
                activeInstance.movementScoreSums[bestExpertOrdinal] = 1.0;
                activeInstance.movementScoreWeights[bestExpertOrdinal] = 1.0;
                activeInstance.applyPersistedLearnedConstant();
                activeInstance.movementSnapshotCache.clear();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable shotdodger-bootstrap payload", e);
        }
    }

    static boolean hasPersistedBootstrapData() {
        return currentBattleDataAvailable || persistedBootstrapLoaded;
    }

    static byte[] createPersistedBootstrapPayload(int maxPayloadBytes,
                                                   boolean includeCurrentBattleData) {
        int bestExpertOrdinal;
        double learnedConstant;
        if (includeCurrentBattleData && currentBattleDataAvailable && activeInstance != null) {
            ShotDodgerExpertId bestExpert = activeInstance.currentBestMovementExpertId();
            bestExpertOrdinal = bestExpert.ordinal();
            learnedConstant = learnedConstantForExpert(activeInstance, bestExpert);
        } else if (persistedBootstrapLoaded) {
            bestExpertOrdinal = persistedBestExpertOrdinal;
            learnedConstant = persistedLearnedConstant;
        } else {
            return null;
        }
        if (maxPayloadBytes < PERSISTED_BOOTSTRAP_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save shotdodger bootstrap: payload budget="
                            + maxPayloadBytes + " bytes");
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(bestExpertOrdinal);
            out.writeDouble(learnedConstant);
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length > maxPayloadBytes) {
                throw new IllegalStateException(
                        "Insufficient data quota to save shotdodger bootstrap: required="
                                + payload.length + " bytes, available=" + maxPayloadBytes + " bytes");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build shotdodger-bootstrap payload", e);
        }
    }

    public static String describeBootstrapStatus() {
        if (persistedBootstrapLoaded) {
            ShotDodgerExpertId expertId = ShotDodgerExpertId.VALUES[persistedBestExpertOrdinal];
            if (Double.isNaN(persistedLearnedConstant)) {
                return "ShotDodger baseline: expert=" + expertId.name();
            }
            return String.format(Locale.US, "ShotDodger baseline: expert=%s, constant=%.2f",
                    expertId.name(), persistedLearnedConstant);
        }
        return "ShotDodger baseline: none";
    }

    private static double learnedConstantForExpert(ShotDodgerObservationProfile instance,
                                                    ShotDodgerExpertId expertId) {
        switch (expertId) {
            case AVERAGED_LINEAR:
                return (double) instance.currentBestAveragedLinearConstant(false);
            case AVERAGED_LINEAR_NO_GUN_ADJUST:
                return (double) instance.currentBestAveragedLinearConstant(true);
            case LINEAR_CONSTANT_DIVISOR:
                return instance.linearConstantDivisorWeight > 0.0
                        ? instance.linearConstantDivisorSum / instance.linearConstantDivisorWeight
                        : Double.NaN;
            case LINEAR_CONSTANT_DIVISOR_NO_GUN_ADJUST:
                return instance.linearConstantDivisorNoAdjustWeight > 0.0
                        ? instance.linearConstantDivisorNoAdjustSum / instance.linearConstantDivisorNoAdjustWeight
                        : Double.NaN;
            default:
                return Double.NaN;
        }
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
