package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.MathUtils;

final class AntiSurferHistoricalWeighting {
    private static final int SIGNATURE_WINDOW = 8;
    private static final double SIGNATURE_DECAY = 0.65;
    private static final double MIN_ACTIVE_WEIGHT = 1e-3;
    private static final int BASE_FEATURE_COUNT = 9;
    private static final double DISTANCE_SCORE_SIGMA = 0.25;

    private final List<HistoricalRecord> gunRecords = new ArrayList<HistoricalRecord>();
    private final List<HistoricalRecord> movementRecords = new ArrayList<HistoricalRecord>();
    private final Deque<GunSignatureRecord> recentGunSignatureRecords = new ArrayDeque<GunSignatureRecord>();
    private final Deque<IntervalSignatureRecord> recentMovementSignatureRecords =
            new ArrayDeque<IntervalSignatureRecord>();

    void clear() {
        gunRecords.clear();
        movementRecords.clear();
        recentGunSignatureRecords.clear();
        recentMovementSignatureRecords.clear();
    }

    void onResolvedGunWave(Wave wave, double gfMin, double gfMax) {
        addGunRecord(wave, gfMin, gfMax);
    }

    void onInvalidatedGunWave(Wave wave, double gfMin, double gfMax) {
        addGunRecord(wave, gfMin, gfMax);
    }

    void onResolvedMovementWave(Wave wave, double gfMin, double gfMax) {
        if (wave == null || wave.fireTimeContext == null || wave.fireTimeRenderGfMarkers == null) {
            return;
        }
        double[] signature = wave.fireTimeHistoricalSignaturePoint != null
                ? wave.fireTimeHistoricalSignaturePoint.clone()
                : currentMovementSignaturePoint();
        movementRecords.add(new HistoricalRecord(
                buildFeatureVector(wave.fireTimeContext, signature, wave.fireTime),
                scoreCentersAgainstInterval(wave.fireTimeRenderGfMarkers, gfMin, gfMax)));
        addRecentIntervalSignatureRecord(recentMovementSignatureRecords, wave.fireTimeRenderGfMarkers, gfMin, gfMax);
    }

    double[] createGunWeights(WaveContextFeatures.WaveContext context, Info info) {
        return createWeights(
                gunRecords,
                buildFeatureVector(context, currentGunSignaturePoint(), currentTime(info)));
    }

    double[] createMovementWeights(WaveContextFeatures.WaveContext context, Info info) {
        return createWeights(
                movementRecords,
                buildFeatureVector(context, currentMovementSignaturePoint(), currentTime(info)));
    }

    private void addGunRecord(Wave wave, double gfMin, double gfMax) {
        if (wave == null || wave.fireTimeContext == null || wave.fireTimeRenderGfMarkers == null) {
            return;
        }
        double[] signature = wave.fireTimeHistoricalSignaturePoint != null
                ? wave.fireTimeHistoricalSignaturePoint.clone()
                : currentGunSignaturePoint();
        gunRecords.add(new HistoricalRecord(
                buildFeatureVector(wave.fireTimeContext, signature, wave.fireTime),
                scoreCentersAgainstInterval(wave.fireTimeRenderGfMarkers, gfMin, gfMax)));
        double firedGf = actualFiredGf(wave);
        if (Double.isFinite(firedGf)) {
            addRecentGunSignatureRecord(wave.fireTimeRenderGfMarkers, firedGf);
        }
    }

    double[] captureCurrentGunSignaturePoint() {
        return currentGunSignaturePoint();
    }

    double[] captureCurrentMovementSignaturePoint() {
        return currentMovementSignaturePoint();
    }

    private double[] createWeights(List<HistoricalRecord> records, double[] currentFeatures) {
        if (records.isEmpty()) {
            return defaultWeights();
        }
        double[] numerator = new double[AntiSurferExpertId.VALUES.length];
        double[] denominator = new double[AntiSurferExpertId.VALUES.length];
        for (HistoricalRecord record : records) {
            double contextWeight = historicalContextWeight(currentFeatures, record.features);
            if (!(contextWeight > 0.0)) {
                continue;
            }
            for (int i = 0; i < AntiSurferExpertId.VALUES.length; i++) {
                double score = record.expertScores[i];
                if (!Double.isFinite(score)) {
                    continue;
                }
                numerator[i] += contextWeight * score;
                denominator[i] += contextWeight;
            }
        }
        double[] weights = new double[AntiSurferExpertId.VALUES.length];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = denominator[i] > 0.0
                    ? Math.max(MIN_ACTIVE_WEIGHT, numerator[i] / denominator[i])
                    : 1.0;
        }
        return weights;
    }

    private static double historicalContextWeight(double[] currentFeatures, double[] recordFeatures) {
        double distanceSquared = 0.0;
        int featureCount = Math.min(currentFeatures.length, recordFeatures.length);
        for (int i = 0; i < featureCount; i++) {
            double delta = currentFeatures[i] - recordFeatures[i];
            distanceSquared += delta * delta;
        }
        return 1.0 / (1.0 + distanceSquared);
    }

    private double[] currentGunSignaturePoint() {
        return buildGunSignaturePoint(recentGunSignatureRecords);
    }

    private double[] currentMovementSignaturePoint() {
        return buildMovementSignaturePoint(recentMovementSignatureRecords);
    }

    private static double[] buildGunSignaturePoint(Deque<GunSignatureRecord> records) {
        double[] numerator = new double[AntiSurferExpertId.VALUES.length];
        double[] denominator = new double[AntiSurferExpertId.VALUES.length];
        double weight = 1.0;
        int used = 0;
        for (Iterator<GunSignatureRecord> it = records.descendingIterator(); it.hasNext() && used < SIGNATURE_WINDOW; used++) {
            GunSignatureRecord record = it.next();
            accumulateGunSignature(record, weight, numerator, denominator);
            weight *= SIGNATURE_DECAY;
        }
        return finalizeSignature(numerator, denominator);
    }

    private static double[] buildMovementSignaturePoint(Deque<IntervalSignatureRecord> records) {
        double[] numerator = new double[AntiSurferExpertId.VALUES.length];
        double[] denominator = new double[AntiSurferExpertId.VALUES.length];
        double weight = 1.0;
        int used = 0;
        for (Iterator<IntervalSignatureRecord> it = records.descendingIterator(); it.hasNext() && used < SIGNATURE_WINDOW; used++) {
            IntervalSignatureRecord record = it.next();
            accumulateIntervalSignature(record, weight, numerator, denominator);
            weight *= SIGNATURE_DECAY;
        }
        return finalizeSignature(numerator, denominator);
    }

    private static void accumulateGunSignature(GunSignatureRecord record,
                                               double weight,
                                               double[] numerator,
                                               double[] denominator) {
        for (int i = 0; i < AntiSurferExpertId.VALUES.length; i++) {
            double center = record.centerAt(i);
            if (!Double.isFinite(center)) {
                continue;
            }
            double distance = center - record.firedGf;
            numerator[i] += weight * Math.min(1.0, distance * distance);
            denominator[i] += weight;
        }
    }

    private static void accumulateIntervalSignature(IntervalSignatureRecord record,
                                                    double weight,
                                                    double[] numerator,
                                                    double[] denominator) {
        for (int i = 0; i < AntiSurferExpertId.VALUES.length; i++) {
            double center = record.centerAt(i);
            if (!Double.isFinite(center)) {
                continue;
            }
            double distance = intervalDistance(center, record.minGf, record.maxGf);
            numerator[i] += weight * Math.min(1.0, distance * distance);
            denominator[i] += weight;
        }
    }

    private static double[] finalizeSignature(double[] numerator, double[] denominator) {
        double[] signature = new double[AntiSurferExpertId.VALUES.length];
        for (int i = 0; i < signature.length; i++) {
            signature[i] = denominator[i] > 0.0 ? numerator[i] / denominator[i] : 1.0;
        }
        return signature;
    }

    private static double[] buildFeatureVector(WaveContextFeatures.WaveContext context,
                                               double[] signature,
                                               long time) {
        if (context == null) {
            double[] empty = new double[BASE_FEATURE_COUNT + AntiSurferExpertId.VALUES.length];
            java.util.Arrays.fill(empty, 0.0);
            return empty;
        }
        double[] features = new double[BASE_FEATURE_COUNT + AntiSurferExpertId.VALUES.length];
        int index = 0;
        features[index++] = clamp01(context.flightTicks / 80.0);
        features[index++] = clamp01(context.wallAhead);
        features[index++] = clamp01(context.wallReverse);
        features[index++] = clamp01(Math.abs(context.lateralVelocity) / 8.0);
        features[index++] = context.accelerationSign > 0 ? 1.0 : context.accelerationSign < 0 ? -1.0 : 0.0;
        features[index++] = clamp01(context.ticksSinceVelocityReversal / (double) Math.max(1, context.flightTicks));
        features[index++] = clamp01(context.ticksSinceDecel / (double) Math.max(1, context.flightTicks));
        features[index++] = clamp01((context.currentGF + 1.0) * 0.5);
        features[index++] = time <= 40L ? 1.0 : 0.0;
        for (int i = 0; i < AntiSurferExpertId.VALUES.length; i++) {
            features[index++] = i < signature.length ? clamp01(signature[i]) : 1.0;
        }
        return features;
    }

    private static double[] scoreCentersAgainstInterval(double[] centers, double minGf, double maxGf) {
        double[] scores = new double[AntiSurferExpertId.VALUES.length];
        for (int i = 0; i < scores.length; i++) {
            double center = i < centers.length ? centers[i] : Double.NaN;
            if (!Double.isFinite(center)) {
                scores[i] = Double.NaN;
                continue;
            }
            scores[i] = scoreCenterAgainstInterval(center, minGf, maxGf);
        }
        return scores;
    }

    private static double scoreCenterAgainstInterval(double center, double minGf, double maxGf) {
        double distance = intervalDistance(center, minGf, maxGf);
        if (!(distance > 0.0)) {
            return 1.0;
        }
        double normalizedDistance = distance / DISTANCE_SCORE_SIGMA;
        return Math.exp(-0.5 * normalizedDistance * normalizedDistance);
    }

    private static double intervalDistance(double center, double minGf, double maxGf) {
        if (center >= minGf && center <= maxGf) {
            return 0.0;
        }
        return center < minGf ? (minGf - center) : (center - maxGf);
    }

    private void addRecentGunSignatureRecord(double[] centers, double firedGf) {
        recentGunSignatureRecords.addLast(new GunSignatureRecord(centers.clone(), firedGf));
        while (recentGunSignatureRecords.size() > SIGNATURE_WINDOW * 2) {
            recentGunSignatureRecords.removeFirst();
        }
    }

    private void addRecentIntervalSignatureRecord(Deque<IntervalSignatureRecord> records,
                                                  double[] centers,
                                                  double minGf,
                                                  double maxGf) {
        records.addLast(new IntervalSignatureRecord(centers.clone(), minGf, maxGf));
        while (records.size() > SIGNATURE_WINDOW * 2) {
            records.removeFirst();
        }
    }

    private static double actualFiredGf(Wave wave) {
        if (wave == null || wave.fireTimeContext == null || !Double.isFinite(wave.heading)) {
            return Double.NaN;
        }
        double referenceBearing = Math.atan2(
                wave.targetX - wave.originX,
                wave.targetY - wave.originY);
        return MathUtils.angleToGf(
                referenceBearing,
                wave.heading,
                MathUtils.maxEscapeAngle(wave.speed));
    }

    private static long currentTime(Info info) {
        return info != null && info.getRobot() != null ? info.getRobot().getTime() : Long.MAX_VALUE;
    }

    private static double[] defaultWeights() {
        double[] weights = new double[AntiSurferExpertId.VALUES.length];
        java.util.Arrays.fill(weights, 1.0);
        return weights;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static final class HistoricalRecord {
        final double[] features;
        final double[] expertScores;

        HistoricalRecord(double[] features, double[] expertScores) {
            this.features = features;
            this.expertScores = expertScores;
        }
    }

    private static final class GunSignatureRecord {
        final double[] centers;
        final double firedGf;

        GunSignatureRecord(double[] centers, double firedGf) {
            this.centers = centers;
            this.firedGf = firedGf;
        }

        double centerAt(int index) {
            return index < centers.length ? centers[index] : Double.NaN;
        }
    }

    private static final class IntervalSignatureRecord {
        final double[] centers;
        final double minGf;
        final double maxGf;

        IntervalSignatureRecord(double[] centers, double minGf, double maxGf) {
            this.centers = centers;
            this.minGf = minGf;
            this.maxGf = maxGf;
        }

        double centerAt(int index) {
            return index < centers.length ? centers[index] : Double.NaN;
        }
    }
}
