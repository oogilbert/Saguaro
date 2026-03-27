package oog.mega.saguaro.mode.shotdodger;

import java.util.Arrays;

import oog.mega.saguaro.info.wave.Wave;

final class ShotDodgerLearnedRecentWeighting {
    private static final int MIN_SAMPLES_FOR_EFFECT = 5;
    private static final double SAMPLE_SATURATION = 16.0;
    private static final double INTERVAL_DISTANCE_SIGMA = 0.25;
    private static final double BASELINE_DENOMINATOR_FLOOR = 0.25;
    private static final double MIN_FACTOR = 0.25;
    private static final double MAX_FACTOR = 4.0;
    private static final double EPSILON = 1e-9;

    private final ExpertRecentModel[] gunModels = createModelArray();
    private final ExpertRecentModel[] movementModels = createModelArray();

    void clear() {
        clearModels(gunModels);
        clearModels(movementModels);
    }

    void onResolvedGunWave(Wave wave, double gfMin, double gfMax) {
        observe(gunModels, wave, gfMin, gfMax);
    }

    void onInvalidatedGunWave(Wave wave, double gfMin, double gfMax) {
        observe(gunModels, wave, gfMin, gfMax);
    }

    void onResolvedMovementWave(Wave wave, double gfMin, double gfMax) {
        observe(movementModels, wave, gfMin, gfMax);
    }

    double[] createGunFactors(double[] currentRecentScores) {
        return createFactors(gunModels, currentRecentScores);
    }

    double[] createMovementFactors(double[] currentRecentScores) {
        return createFactors(movementModels, currentRecentScores);
    }

    private static void observe(ExpertRecentModel[] models, Wave wave, double gfMin, double gfMax) {
        if (wave == null || wave.fireTimeRecentExpertScores == null || wave.fireTimeRenderGfMarkers == null) {
            return;
        }
        int expertCount = Math.min(
                ShotDodgerExpertId.VALUES.length,
                Math.min(wave.fireTimeRecentExpertScores.length, wave.fireTimeRenderGfMarkers.length));
        for (int i = 0; i < expertCount; i++) {
            double recentScore = wave.fireTimeRecentExpertScores[i];
            double center = wave.fireTimeRenderGfMarkers[i];
            if (!Double.isFinite(recentScore) || !Double.isFinite(center)) {
                continue;
            }
            double actualScore = scoreCenterAgainstInterval(center, gfMin, gfMax);
            models[i].observe(recentScore, actualScore);
        }
    }

    private static double[] createFactors(ExpertRecentModel[] models, double[] currentRecentScores) {
        double[] factors = new double[ShotDodgerExpertId.VALUES.length];
        Arrays.fill(factors, 1.0);
        if (currentRecentScores == null) {
            return factors;
        }
        int expertCount = Math.min(factors.length, currentRecentScores.length);
        for (int i = 0; i < expertCount; i++) {
            double recentScore = currentRecentScores[i];
            if (!Double.isFinite(recentScore)) {
                continue;
            }
            factors[i] = models[i].factorFor(recentScore);
        }
        return factors;
    }

    private static double scoreCenterAgainstInterval(double center, double minGf, double maxGf) {
        if (center >= minGf && center <= maxGf) {
            return 1.0;
        }
        double distance = center < minGf ? (minGf - center) : (center - maxGf);
        double normalizedDistance = distance / INTERVAL_DISTANCE_SIGMA;
        return Math.exp(-0.5 * normalizedDistance * normalizedDistance);
    }

    private static ExpertRecentModel[] createModelArray() {
        ExpertRecentModel[] models = new ExpertRecentModel[ShotDodgerExpertId.VALUES.length];
        for (int i = 0; i < models.length; i++) {
            models[i] = new ExpertRecentModel();
        }
        return models;
    }

    private static void clearModels(ExpertRecentModel[] models) {
        for (ExpertRecentModel model : models) {
            model.clear();
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ExpertRecentModel {
        private int sampleCount;
        private double meanX;
        private double meanY;
        private double sxx;
        private double syy;
        private double sxy;

        void clear() {
            sampleCount = 0;
            meanX = 0.0;
            meanY = 0.0;
            sxx = 0.0;
            syy = 0.0;
            sxy = 0.0;
        }

        void observe(double x, double y) {
            sampleCount++;
            double dx = x - meanX;
            meanX += dx / sampleCount;
            double dy = y - meanY;
            meanY += dy / sampleCount;
            sxx += dx * (x - meanX);
            syy += dy * (y - meanY);
            sxy += dx * (y - meanY);
        }

        double factorFor(double x) {
            if (sampleCount < MIN_SAMPLES_FOR_EFFECT || sxx <= EPSILON || syy <= EPSILON) {
                return 1.0;
            }
            double correlation = clamp(sxy / Math.sqrt(sxx * syy), -1.0, 1.0);
            double slope = sxy / sxx;
            double baseline = clamp(meanY, 0.0, 1.0);
            double predicted = clamp(meanY + slope * (x - meanX), 0.0, 1.0);
            double evidence = Math.min(1.0, (sampleCount - 1) / SAMPLE_SATURATION);
            double reliability = evidence * Math.abs(correlation);
            double normalizedDelta = (predicted - baseline) / Math.max(BASELINE_DENOMINATOR_FLOOR, baseline);
            double factor = 1.0 + reliability * normalizedDelta;
            return clamp(factor, MIN_FACTOR, MAX_FACTOR);
        }
    }
}
