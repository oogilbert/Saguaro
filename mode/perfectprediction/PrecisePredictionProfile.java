package oog.mega.saguaro.mode.perfectprediction;

public final class PrecisePredictionProfile {
    private static final int MAX_RECENT_SAMPLES = 500;
    private static final ReactivePredictorId[] PREDICTOR_IDS = ReactivePredictorId.values();

    private static final int[] rawSampleCounts = new int[PREDICTOR_IDS.length];
    private static final int[] nextSampleIndices = new int[PREDICTOR_IDS.length];
    private static final double[] totalSampleWeights = new double[PREDICTOR_IDS.length];
    private static final double[] weightedErrorSums = new double[PREDICTOR_IDS.length];
    private static final double[] weightedSquaredErrorSums = new double[PREDICTOR_IDS.length];
    private static final double[][] sampleWeights = new double[PREDICTOR_IDS.length][MAX_RECENT_SAMPLES];
    private static final double[][] weightedErrorSamples = new double[PREDICTOR_IDS.length][MAX_RECENT_SAMPLES];
    private static final double[][] weightedSquaredErrorSamples = new double[PREDICTOR_IDS.length][MAX_RECENT_SAMPLES];

    private PrecisePredictionProfile() {
    }

    public static void startBattle() {
        clear();
    }

    public static void recordPredictionError(ReactivePredictorId predictorId, double error, double sampleWeight) {
        if (predictorId == null) {
            throw new IllegalArgumentException("Precise-prediction profile requires a non-null predictor id");
        }
        if (!Double.isFinite(error) || error < 0.0) {
            throw new IllegalArgumentException("Precise-prediction profile requires a finite non-negative error");
        }
        if (!Double.isFinite(sampleWeight) || sampleWeight <= 0.0 || sampleWeight > 1.0 + 1e-9) {
            throw new IllegalArgumentException("Precise-prediction profile requires a finite sample weight in (0, 1]");
        }
        int index = predictorId.ordinal();
        int slot = nextSampleIndices[index];
        if (rawSampleCounts[index] == MAX_RECENT_SAMPLES) {
            totalSampleWeights[index] -= sampleWeights[index][slot];
            weightedErrorSums[index] -= weightedErrorSamples[index][slot];
            weightedSquaredErrorSums[index] -= weightedSquaredErrorSamples[index][slot];
        } else {
            rawSampleCounts[index]++;
        }

        double weightedError = sampleWeight * error;
        double weightedSquaredError = weightedError * error;
        sampleWeights[index][slot] = sampleWeight;
        weightedErrorSamples[index][slot] = weightedError;
        weightedSquaredErrorSamples[index][slot] = weightedSquaredError;
        totalSampleWeights[index] += sampleWeight;
        weightedErrorSums[index] += weightedError;
        weightedSquaredErrorSums[index] += weightedSquaredError;
        nextSampleIndices[index] = (slot + 1) % MAX_RECENT_SAMPLES;
    }

    public static int getCombinedRawSampleCount(ReactivePredictorId predictorId) {
        return rawSampleCounts[predictorId.ordinal()];
    }

    public static double getCombinedWeightedSampleCount(ReactivePredictorId predictorId) {
        return getTotalSampleWeight(predictorId);
    }

    public static double getCombinedMeanError(ReactivePredictorId predictorId) {
        double sampleWeight = getTotalSampleWeight(predictorId);
        if (!(sampleWeight > 0.0)) {
            return Double.NaN;
        }
        return getWeightedErrorSum(predictorId) / sampleWeight;
    }

    public static double getCombinedMeanErrorUpperBound(ReactivePredictorId predictorId, double confidenceScale) {
        double meanError = getCombinedMeanError(predictorId);
        double standardError = getCombinedMeanErrorStandardError(predictorId);
        if (Double.isNaN(meanError) || Double.isNaN(standardError)) {
            return Double.NaN;
        }
        return meanError + confidenceScale * standardError;
    }

    public static double getCombinedMeanErrorLowerBound(ReactivePredictorId predictorId, double confidenceScale) {
        double meanError = getCombinedMeanError(predictorId);
        double standardError = getCombinedMeanErrorStandardError(predictorId);
        if (Double.isNaN(meanError) || Double.isNaN(standardError)) {
            return Double.NaN;
        }
        return Math.max(0.0, meanError - confidenceScale * standardError);
    }

    public static boolean isPredictorUnlocked(ReactivePredictorId predictorId,
                                              int minSampleCount,
                                              double maxMeanError,
                                              double confidenceScale) {
        if (getCombinedRawSampleCount(predictorId) < minSampleCount) {
            return false;
        }
        double upperBound = getCombinedMeanErrorUpperBound(predictorId, confidenceScale);
        return !Double.isNaN(upperBound) && upperBound <= maxMeanError;
    }

    public static boolean isAnyPredictorUnlocked(int minSampleCount,
                                                 double maxMeanError,
                                                 double confidenceScale) {
        return bestPredictorId(true, minSampleCount, maxMeanError, confidenceScale) != null;
    }

    public static ReactivePredictorId bestPredictorId(boolean unlockedOnly,
                                                      int minSampleCount,
                                                      double maxMeanError,
                                                      double confidenceScale) {
        ReactivePredictorId bestPredictor = null;
        double bestUpperBound = Double.POSITIVE_INFINITY;
        double bestMeanError = Double.POSITIVE_INFINITY;
        double bestWeightedSampleCount = Double.NEGATIVE_INFINITY;
        int bestRawSampleCount = -1;
        for (ReactivePredictorId predictorId : PREDICTOR_IDS) {
            int rawSampleCount = getCombinedRawSampleCount(predictorId);
            if (rawSampleCount <= 0 || !(getTotalSampleWeight(predictorId) > 0.0)) {
                continue;
            }
            if (unlockedOnly && !isPredictorUnlocked(predictorId, minSampleCount, maxMeanError, confidenceScale)) {
                continue;
            }
            double upperBound = getCombinedMeanErrorUpperBound(predictorId, confidenceScale);
            double meanError = getCombinedMeanError(predictorId);
            double weightedSampleCount = getCombinedWeightedSampleCount(predictorId);
            if (Double.isNaN(upperBound) || Double.isNaN(meanError) || !Double.isFinite(weightedSampleCount)) {
                continue;
            }
            if (bestPredictor == null
                    || upperBound < bestUpperBound - 1e-9
                    || (Math.abs(upperBound - bestUpperBound) <= 1e-9
                    && meanError < bestMeanError - 1e-9)
                    || (Math.abs(upperBound - bestUpperBound) <= 1e-9
                    && Math.abs(meanError - bestMeanError) <= 1e-9
                    && weightedSampleCount > bestWeightedSampleCount + 1e-9)
                    || (Math.abs(upperBound - bestUpperBound) <= 1e-9
                    && Math.abs(meanError - bestMeanError) <= 1e-9
                    && Math.abs(weightedSampleCount - bestWeightedSampleCount) <= 1e-9
                    && rawSampleCount > bestRawSampleCount)) {
                bestPredictor = predictorId;
                bestUpperBound = upperBound;
                bestMeanError = meanError;
                bestWeightedSampleCount = weightedSampleCount;
                bestRawSampleCount = rawSampleCount;
            }
        }
        return bestPredictor;
    }

    private static double getCombinedMeanErrorStandardError(ReactivePredictorId predictorId) {
        double sampleWeight = getTotalSampleWeight(predictorId);
        if (!(sampleWeight > 0.0)) {
            return Double.NaN;
        }
        if (sampleWeight <= 1.0 + 1e-9) {
            return 0.0;
        }
        double errorSum = getWeightedErrorSum(predictorId);
        double squaredErrorSum = getWeightedSquaredErrorSum(predictorId);
        double numerator = squaredErrorSum - (errorSum * errorSum) / sampleWeight;
        double sampleVariance = Math.max(0.0, numerator / (sampleWeight - 1.0));
        return Math.sqrt(sampleVariance / sampleWeight);
    }

    private static double getTotalSampleWeight(ReactivePredictorId predictorId) {
        return totalSampleWeights[predictorId.ordinal()];
    }

    private static double getWeightedErrorSum(ReactivePredictorId predictorId) {
        return weightedErrorSums[predictorId.ordinal()];
    }

    private static double getWeightedSquaredErrorSum(ReactivePredictorId predictorId) {
        return weightedSquaredErrorSums[predictorId.ordinal()];
    }

    private static void clear() {
        for (int i = 0; i < rawSampleCounts.length; i++) {
            rawSampleCounts[i] = 0;
            nextSampleIndices[i] = 0;
            totalSampleWeights[i] = 0.0;
            weightedErrorSums[i] = 0.0;
            weightedSquaredErrorSums[i] = 0.0;
            for (int j = 0; j < MAX_RECENT_SAMPLES; j++) {
                sampleWeights[i][j] = 0.0;
                weightedErrorSamples[i][j] = 0.0;
                weightedSquaredErrorSamples[i][j] = 0.0;
            }
        }
    }
}
