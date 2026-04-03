package oog.mega.saguaro.info.wave;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ags.utils.dataStructures.MaxHeap;
import ags.utils.dataStructures.trees.thirdGenKD.KdTree;
import ags.utils.dataStructures.trees.thirdGenKD.WeightedSquareEuclideanDistanceFunction;
import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.IntervalKDEDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.MirroredGuessFactorDistribution;

public class WaveLog {
    static final int CONTEXT_DIMENSIONS = 22;
    private static final int FEATURE_POWER = 0;
    private static final int FEATURE_BFT = 1;
    private static final int FEATURE_ACCEL = 2;
    private static final int FEATURE_VEL_ABS = 3;
    private static final int FEATURE_VEL_MAX = 4;
    private static final int FEATURE_LAT_VEL = 5;
    private static final int FEATURE_ADV_VEL = 6;
    private static final int FEATURE_REVERSAL = 7;
    private static final int FEATURE_DECEL = 8;
    private static final int FEATURE_DIST10 = 9;
    private static final int FEATURE_DIST20 = 10;
    private static final int FEATURE_RELATIVE_HEADING = 11;
    private static final int FEATURE_MAE_WALL_AHEAD = 12;
    private static final int FEATURE_MAE_WALL_REVERSE = 13;
    private static final int FEATURE_STICK_WALL_AHEAD = 14;
    private static final int FEATURE_STICK_WALL_REVERSE = 15;
    private static final int FEATURE_STICK_WALL_AHEAD2 = 16;
    private static final int FEATURE_STICK_WALL_REVERSE2 = 17;
    private static final int FEATURE_CURRENT_GF = 18;
    private static final int FEATURE_DID_HIT = 19;
    private static final int FEATURE_BATTLE_TIME = 20;
    private static final int FEATURE_SHOTS_FIRED = 21;
    private static final int MODEL_CANDIDATE_POOL = 7;
    private static final int GRADIENT_CANDIDATE_POOL = 50;
    private static final double WEIGHT_SOFT_FLOOR = 0.01;
    private static final int PERSISTENCE_SECTION_VERSION = 5;
    private static final int PERSISTED_MODEL_VALUE_COUNT = CONTEXT_DIMENSIONS * 3 + 2;
    private static final int PERSISTED_MODEL_BYTES = PERSISTED_MODEL_VALUE_COUNT * Short.BYTES;
    private static final int PERSISTED_TARGETING_MODEL_MASK = 0x01;
    private static final int PERSISTED_MOVEMENT_MODEL_MASK = 0x02;
    private static final int PERSISTED_MODEL_COMPONENT_MASK_BYTES = 1;
    private static final double PERSISTED_UNSIGNED_SHORT_MAX = 65535.0;
    private static final double MIN_PERSISTED_WEIGHT = WEIGHT_SOFT_FLOOR;
    private static final double MAX_PERSISTED_WEIGHT = 32.0;
    private static final double MAX_LATERAL_VELOCITY = 8.0;
    private static final double MIN_VELOCITY_DELTA = -2.0;
    private static final double MAX_VELOCITY_DELTA = 1.0;
    private static final double FLIGHT_TICKS_SCALE = 100.0;
    private static final double RELATIVE_TICKS_CAP = 70.0;
    private static final double DISTANCE_LAST_10_SCALE = 10.0 * MAX_LATERAL_VELOCITY;
    private static final double DISTANCE_LAST_20_SCALE = 20.0 * MAX_LATERAL_VELOCITY;
    private static final double BATTLE_TIME_SCALE = 500.0;
    private static final double SHOTS_FIRED_SCALE = 1000.0;
    private static final double FEATURE_TRANSFORM_EPSILON = 1e-4;
    private static final double MIN_FEATURE_BIAS = 0.0;
    private static final double MAX_FEATURE_BIAS = 1.0;
    private static final double MIN_FEATURE_EXPONENT = 0.15;
    private static final double MAX_FEATURE_EXPONENT = 2.0;
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final double MIN_INTERVAL_WIDTH = 1e-9;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);
    private static final double WEIGHT_ZERO_EPSILON = 1e-6;
    private static final double MODEL_EPSILON = 1e-9;
    private static final double PROBABILITY_EPSILON = 1e-12;
    private static final double BANDWIDTH_FINITE_DIFFERENCE_LOG_DELTA = 0.04;
    private static final double[] DEFAULT_TARGETING_DISTANCE_WEIGHTS = new double[]{
            0.65,
            3.86,
            0.90,
            1.53,
            0.65,
            0.20,
            0.90,
            1.15,
            1.00,
            0.15,
            1.30,
            0.90,
            2.40,
            2.10,
            1.60,
            1.40,
            1.30,
            1.10,
            1.05,
            0.25,
            0.55,
            0.35
    };
    private static final double[] DEFAULT_MOVEMENT_DISTANCE_WEIGHTS = new double[]{
            0.45,
            1.90,
            0.90,
            1.60,
            0.20,
            2.30,
            0.60,
            1.20,
            1.10,
            1.10,
            0.90,
            0.70,
            1.60,
            1.20,
            1.20,
            1.00,
            0.80,
            0.70,
            0.75,
            2.00,
            0.85,
            0.40
    };
    private static final double[] DEFAULT_TARGETING_FEATURE_BIASES = new double[]{
            0.02, 0.07, 0.02, 0.36, 0.00, 0.05, 0.05, 0.03, 0.03, 0.02, 0.02,
            0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.00, 0.02, 0.02
    };
    private static final double[] DEFAULT_TARGETING_FEATURE_EXPONENTS = new double[]{
            0.38, 0.55, 0.80, 0.50, 1.00, 0.65, 0.65, 0.75, 0.75, 0.70, 0.70,
            0.60, 0.35, 0.35, 0.45, 0.45, 0.45, 0.45, 0.85, 1.00, 0.50, 0.50
    };
    private static final double[] DEFAULT_MOVEMENT_FEATURE_BIASES = new double[]{
            0.02, 0.05, 0.02, 0.18, 0.00, 0.05, 0.05, 0.03, 0.03, 0.02, 0.02,
            0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.00, 0.02, 0.02
    };
    private static final double[] DEFAULT_MOVEMENT_FEATURE_EXPONENTS = new double[]{
            0.45, 0.55, 0.80, 0.55, 1.00, 0.65, 0.60, 0.80, 0.75, 0.70, 0.70,
            0.55, 0.40, 0.40, 0.50, 0.50, 0.50, 0.50, 0.85, 1.00, 0.55, 0.55
    };
    private static final ModelSpec DEFAULT_TARGETING_MODEL = new ModelSpec(
            DEFAULT_TARGETING_DISTANCE_WEIGHTS,
            BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH,
            BotConfig.Learning.DEFAULT_TARGETING_CONTEXT_WEIGHT_SIGMA,
            DEFAULT_TARGETING_FEATURE_BIASES,
            DEFAULT_TARGETING_FEATURE_EXPONENTS);
    private static final ModelSpec DEFAULT_MOVEMENT_MODEL = new ModelSpec(
            DEFAULT_MOVEMENT_DISTANCE_WEIGHTS,
            BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH,
            BotConfig.Learning.DEFAULT_MOVEMENT_CONTEXT_WEIGHT_SIGMA,
            DEFAULT_MOVEMENT_FEATURE_BIASES,
            DEFAULT_MOVEMENT_FEATURE_EXPONENTS);
    private static final SegmentLog gunSegment = new SegmentLog("targeting", DEFAULT_TARGETING_MODEL, true);
    private static final SegmentLog movementSegment = new SegmentLog("surfing", DEFAULT_MOVEMENT_MODEL, false);
    private static final int TRACE_BUFFER_CAPACITY = 80;
    private static final int TRACE_DUMP_LINES_ON_FAILURE = 24;
    private static final Object TRACE_LOCK = new Object();
    private static final TraceEntry[] traceBuffer = new TraceEntry[TRACE_BUFFER_CAPACITY];
    private static long traceSequence = 0L;
    private static int traceWriteIndex = 0;
    private static int traceCount = 0;
    private static boolean persistedSectionLoaded;
    private static boolean currentBattleModelUpdated;
    private static ModelSpec persistedTargetingModel;
    private static ModelSpec persistedMovementModel;

    private static final class TraceEntry {
        final long seq;
        final String stage;
        final String segment;
        final String source;
        final long threadId;
        final String threadName;
        final int size;

        TraceEntry(long seq,
                   String stage,
                   String segment,
                   String source,
                   long threadId,
                   String threadName,
                   int size) {
            this.seq = seq;
            this.stage = stage;
            this.segment = segment;
            this.source = source;
            this.threadId = threadId;
            this.threadName = threadName;
            this.size = size;
        }
    }

    static final class DataPoint {
        final double[] normalizedContextPoint;
        double[] contextPoint;
        final double gfMin;
        final double gfMax;

        DataPoint(double[] normalizedContextPoint,
                  double[] contextPoint,
                  double gfMin,
                  double gfMax) {
            this.normalizedContextPoint = normalizedContextPoint;
            this.contextPoint = contextPoint;
            this.gfMin = gfMin;
            this.gfMax = gfMax;
        }
    }

    private static final class ModelSpec {
        final double[] contextDistanceWeights;
        final double kdeBandwidth;
        final double contextWeightSigma;
        final double[] featureBiases;
        final double[] featureExponents;

        ModelSpec(double[] contextDistanceWeights,
                  double kdeBandwidth,
                  double contextWeightSigma,
                  double[] featureBiases,
                  double[] featureExponents) {
            if (contextDistanceWeights == null || contextDistanceWeights.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Model weights must match context dimension count");
            }
            if (featureBiases == null || featureBiases.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Model feature biases must match context dimension count");
            }
            if (featureExponents == null || featureExponents.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Model feature exponents must match context dimension count");
            }
            this.contextDistanceWeights = contextDistanceWeights.clone();
            this.kdeBandwidth = kdeBandwidth;
            this.contextWeightSigma = contextWeightSigma;
            this.featureBiases = featureBiases.clone();
            this.featureExponents = featureExponents.clone();
        }
    }

    private static final class NeighborSelection {
        final DataPoint[] candidatePool;
        final double[] gfMins;
        final double[] gfMaxs;
        final double[] weights;

        NeighborSelection(DataPoint[] candidatePool,
                          double[] gfMins,
                          double[] gfMaxs,
                          double[] weights) {
            this.candidatePool = candidatePool;
            this.gfMins = gfMins;
            this.gfMaxs = gfMaxs;
            this.weights = weights;
        }
    }

    private static final class KernelEvaluation {
        final double[] signalKernels;
        final double[] totalKernels;
        final double signalSum;
        final double totalSum;

        KernelEvaluation(double[] signalKernels,
                         double[] totalKernels,
                         double signalSum,
                         double totalSum) {
            this.signalKernels = signalKernels;
            this.totalKernels = totalKernels;
            this.signalSum = signalSum;
            this.totalSum = totalSum;
        }
    }

    static final class SegmentLog {
        final String label;
        final ModelSpec defaultModel;
        final boolean intervalSamples;
        KdTree<DataPoint> log = new KdTree<DataPoint>(CONTEXT_DIMENSIONS);
        final List<DataPoint> currentBattleSamples = new ArrayList<>();
        final double[] contextDistanceWeights = new double[CONTEXT_DIMENSIONS];
        final double[] featureBiases = new double[CONTEXT_DIMENSIONS];
        final double[] featureExponents = new double[CONTEXT_DIMENSIONS];
        final double[] kdTreeDistanceWeights = new double[CONTEXT_DIMENSIONS];
        final WeightedSquareEuclideanDistanceFunction distanceFunction =
                new WeightedSquareEuclideanDistanceFunction(kdTreeDistanceWeights);
        final double defaultWeightMass;
        double kdeBandwidth = BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH;
        double contextWeightSigma = BotConfig.Learning.DEFAULT_TARGETING_CONTEXT_WEIGHT_SIGMA;

        SegmentLog(String label, ModelSpec defaultModel, boolean intervalSamples) {
            this.label = label;
            this.defaultModel = defaultModel;
            this.intervalSamples = intervalSamples;
            this.defaultWeightMass = sumPositiveWeights(defaultModel.contextDistanceWeights);
            resetModelToDefault();
        }

        void resetBattleState() {
            log = new KdTree<DataPoint>(CONTEXT_DIMENSIONS);
            currentBattleSamples.clear();
        }

        void resetModelToDefault() {
            applyModel(defaultModel);
        }

        void applyModel(ModelSpec model) {
            if (model == null) {
                throw new IllegalArgumentException("Segment model must be non-null");
            }
            applyModel(
                    model.contextDistanceWeights,
                    model.kdeBandwidth,
                    model.contextWeightSigma,
                    model.featureBiases,
                    model.featureExponents);
        }

        void applyModel(double[] weights,
                        double kdeBandwidth,
                        double contextWeightSigma,
                        double[] featureBiases,
                        double[] featureExponents) {
            if (weights == null || weights.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Segment weights must match context dimension count");
            }
            if (featureBiases == null || featureBiases.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Segment feature biases must match context dimension count");
            }
            if (featureExponents == null || featureExponents.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Segment feature exponents must match context dimension count");
            }
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                double weight = weights[i];
                contextDistanceWeights[i] = Double.isFinite(weight) && weight > 0.0 ? weight : 0.0;
            }
            if (!normalizeWeightMass(contextDistanceWeights, defaultWeightMass)) {
                System.arraycopy(defaultModel.contextDistanceWeights, 0, contextDistanceWeights, 0, CONTEXT_DIMENSIONS);
            }
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                double bias = featureBiases[i];
                double exponent = featureExponents[i];
                this.featureBiases[i] = clamp(
                        Double.isFinite(bias) ? bias : defaultModel.featureBiases[i],
                        MIN_FEATURE_BIAS,
                        MAX_FEATURE_BIAS);
                this.featureExponents[i] = clamp(
                        Double.isFinite(exponent) ? exponent : defaultModel.featureExponents[i],
                        MIN_FEATURE_EXPONENT,
                        MAX_FEATURE_EXPONENT);
            }
            syncDistanceFunctionWeights();
            this.kdeBandwidth = clamp(
                    kdeBandwidth,
                    BotConfig.Learning.MIN_KDE_BANDWIDTH,
                    BotConfig.Learning.MAX_KDE_BANDWIDTH);
            this.contextWeightSigma = clamp(
                    contextWeightSigma,
                    BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                    BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);
        }

        void rebuildIndex() {
            KdTree<DataPoint> rebuilt = new KdTree<DataPoint>(CONTEXT_DIMENSIONS);
            for (DataPoint dataPoint : currentBattleSamples) {
                dataPoint.contextPoint = createEmbeddedContextPoint(this, dataPoint.normalizedContextPoint);
                rebuilt.addPoint(dataPoint.contextPoint, dataPoint);
            }
            log = rebuilt;
        }

        private void syncDistanceFunctionWeights() {
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                kdTreeDistanceWeights[i] = Math.sqrt(Math.max(0.0, contextDistanceWeights[i]));
            }
        }
    }

    public static void logGunResult(WaveContextFeatures.WaveContext context, double gf) {
        logGunResult(context, gf, true);
    }

    public static void logGunResult(WaveContextFeatures.WaveContext context,
                                    double gf,
                                    boolean saveObservation) {
        logGunInterval(context, gf, gf, saveObservation);
    }

    public static void logGunInterval(WaveContextFeatures.WaveContext context,
                                      double gfMin,
                                      double gfMax,
                                      boolean saveObservation) {
        logResult(gunSegment, context, gfMin, gfMax, saveObservation, false, null);
    }

    public static void logGunInterval(WaveContextFeatures.WaveContext context,
                                      double gfMin,
                                      double gfMax,
                                      boolean saveObservation,
                                      boolean updateModel) {
        logResult(gunSegment, context, gfMin, gfMax, saveObservation, updateModel, null);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context, double gf) {
        logMovementResult(context, gf, true);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation) {
        logMovementResult(context, gf, saveObservation, false, true);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation,
                                         boolean updateModel) {
        logMovementResult(context, gf, saveObservation, updateModel, true);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation,
                                         boolean updateModel,
                                         boolean actualBulletObservation) {
        logResult(
                movementSegment,
                context,
                gf,
                gf,
                saveObservation,
                updateModel,
                Boolean.valueOf(actualBulletObservation));
    }

    public static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        if (gunSegment.log.size() < BotConfig.Learning.MIN_TARGETING_SAMPLE_COUNT) {
            return null;
        }
        return createDistribution(gunSegment, context, null);
    }

    public static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        if (movementSegment.log.size() < BotConfig.Learning.MIN_MOVEMENT_SAMPLE_COUNT) {
            return null;
        }
        return createDistribution(movementSegment, context, Boolean.TRUE);
    }

    private static void logResult(SegmentLog segment,
                                  WaveContextFeatures.WaveContext context,
                                  double gfMin,
                                  double gfMax,
                                  boolean saveObservation,
                                  boolean updateModel,
                                  Boolean didHitOverride) {
        if (!Double.isFinite(gfMin) || !Double.isFinite(gfMax) || gfMin > gfMax) {
            throw new IllegalArgumentException("Wave-log result requires ordered finite GF bounds");
        }
        double[] normalizedContextPoint = createNormalizedContextPoint(context, didHitOverride);
        double[] canonicalGfRange = canonicalizeGuessFactorRange(gfMin, gfMax, context.momentumDirectionSign);
        double canonicalGfMin = canonicalGfRange[0];
        double canonicalGfMax = canonicalGfRange[1];
        if (updateModel) {
            updateModelFromObservation(segment, normalizedContextPoint, canonicalGfMin, canonicalGfMax);
        }
        addDataPoint(
                segment,
                new DataPoint(
                        normalizedContextPoint,
                        createEmbeddedContextPoint(segment, normalizedContextPoint),
                        canonicalGfMin,
                        canonicalGfMax),
                saveObservation);
    }

    private static void addDataPoint(SegmentLog segment,
                                     DataPoint dataPoint,
                                     boolean saveObservation) {
        String source = BotConfig.Debug.ENABLE_TRACE_SOURCE_CAPTURE ? inferLogSource() : "disabled";
        int sizeBefore = segment.log.size();
        addTrace("before", segment.label, source, sizeBefore);
        try {
            segment.log.addPoint(dataPoint.contextPoint, dataPoint);
        } catch (ArrayIndexOutOfBoundsException e) {
            return;
        } catch (RuntimeException e) {
            addTrace("fail", segment.label, source, safeTreeSize(segment));
            dumpTraceOnFailure(segment.label, source, sizeBefore, e);
            throw e;
        } catch (Error e) {
            addTrace("fail", segment.label, source, safeTreeSize(segment));
            dumpTraceOnFailure(segment.label, source, sizeBefore, e);
            throw e;
        }
        addTrace("after", segment.label, source, segment.log.size());
        segment.currentBattleSamples.add(dataPoint);
    }

    private static boolean updateModelFromObservation(SegmentLog segment,
                                                      double[] normalizedQueryPoint,
                                                      double observationGfMin,
                                                      double observationGfMax) {
        if (segment == null || normalizedQueryPoint == null || segment.log.size() <= 0) {
            return false;
        }
        double[] queryPoint = createEmbeddedContextPoint(segment, normalizedQueryPoint);
        NeighborSelection selection = selectNeighbors(
                segment,
                queryPoint,
                gradientCandidateCountForLogSize(segment.log.size()));
        if (selection == null) {
            return false;
        }
        KernelEvaluation kernelEvaluation = evaluateKernels(
                segment,
                selection,
                observationGfMin,
                observationGfMax,
                segment.kdeBandwidth);
        if (!(kernelEvaluation.signalSum > PROBABILITY_EPSILON)
                || !(kernelEvaluation.totalSum > PROBABILITY_EPSILON)) {
            return false;
        }

        double inverseSignalSum = 1.0 / Math.max(PROBABILITY_EPSILON, kernelEvaluation.signalSum);
        double inverseTotalSum = 1.0 / Math.max(PROBABILITY_EPSILON, kernelEvaluation.totalSum);
        double sigma = segment.contextWeightSigma;
        double sigmaSq = sigma * sigma;
        double[] queryBiasDerivatives = new double[CONTEXT_DIMENSIONS];
        double[] queryExponentDerivatives = new double[CONTEXT_DIMENSIONS];
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            queryBiasDerivatives[i] = transformFeatureDerivativeBias(
                    normalizedQueryPoint[i],
                    segment.featureBiases[i],
                    segment.featureExponents[i]);
            queryExponentDerivatives[i] = transformFeatureDerivativeExponent(
                    normalizedQueryPoint[i],
                    segment.featureBiases[i],
                    segment.featureExponents[i]);
        }

        double[] weightGradients = new double[CONTEXT_DIMENSIONS];
        double[] biasGradients = new double[CONTEXT_DIMENSIONS];
        double[] exponentGradients = new double[CONTEXT_DIMENSIONS];
        double logSigmaGradient = 0.0;

        for (int sampleIndex = 0; sampleIndex < selection.candidatePool.length; sampleIndex++) {
            DataPoint candidate = selection.candidatePool[sampleIndex];
            double contextWeight = selection.weights[sampleIndex];
            if (!(contextWeight > PROBABILITY_EPSILON)) {
                continue;
            }
            double signalKernel = kernelEvaluation.signalKernels[sampleIndex];
            double totalKernel = kernelEvaluation.totalKernels[sampleIndex];
            double weightedDistance = weightedSquaredDistance(
                    candidate.contextPoint,
                    queryPoint,
                    segment.contextDistanceWeights);
            double sigmaWeightGradientFactor = contextWeight / sigmaSq;
            for (int featureIndex = 0; featureIndex < CONTEXT_DIMENSIONS; featureIndex++) {
                double diff = candidate.contextPoint[featureIndex] - queryPoint[featureIndex];
                double diffSq = diff * diff;
                double sharedContextGradient =
                        totalKernel * inverseTotalSum - signalKernel * inverseSignalSum;
                weightGradients[featureIndex] +=
                        sharedContextGradient * sigmaWeightGradientFactor * (-0.5 * diffSq);

                double candidateBiasDerivative = transformFeatureDerivativeBias(
                        candidate.normalizedContextPoint[featureIndex],
                        segment.featureBiases[featureIndex],
                        segment.featureExponents[featureIndex]);
                double distanceBiasDerivative =
                        2.0
                                * segment.contextDistanceWeights[featureIndex]
                                * diff
                                * (candidateBiasDerivative - queryBiasDerivatives[featureIndex]);
                biasGradients[featureIndex] +=
                        sharedContextGradient
                                * sigmaWeightGradientFactor
                                * (-0.5 * distanceBiasDerivative);

                double candidateExponentDerivative = transformFeatureDerivativeExponent(
                        candidate.normalizedContextPoint[featureIndex],
                        segment.featureBiases[featureIndex],
                        segment.featureExponents[featureIndex]);
                double distanceExponentDerivative =
                        2.0
                                * segment.contextDistanceWeights[featureIndex]
                                * diff
                                * (candidateExponentDerivative - queryExponentDerivatives[featureIndex]);
                exponentGradients[featureIndex] +=
                        sharedContextGradient
                                * sigmaWeightGradientFactor
                                * (-0.5 * distanceExponentDerivative);
            }
            logSigmaGradient +=
                    (totalKernel * inverseTotalSum - signalKernel * inverseSignalSum)
                            * contextWeight
                            * weightedDistance
                            / sigmaSq;
        }

        double logBandwidthGradient = finiteDifferenceLogBandwidthGradient(
                segment,
                selection,
                observationGfMin,
                observationGfMax,
                kernelEvaluation.signalSum,
                kernelEvaluation.totalSum);

        boolean changed = applyLearningStep(
                segment,
                weightGradients,
                biasGradients,
                exponentGradients,
                logBandwidthGradient,
                logSigmaGradient);
        if (changed) {
            segment.rebuildIndex();
            currentBattleModelUpdated = true;
        }
        return changed;
    }

    private static boolean applyLearningStep(SegmentLog segment,
                                             double[] weightGradients,
                                             double[] biasGradients,
                                             double[] exponentGradients,
                                             double logBandwidthGradient,
                                             double logSigmaGradient) {
        double[] previousWeights = segment.contextDistanceWeights.clone();
        double[] previousBiases = segment.featureBiases.clone();
        double[] previousExponents = segment.featureExponents.clone();
        double previousBandwidth = segment.kdeBandwidth;
        double previousSigma = segment.contextWeightSigma;

        double[] updatedWeights = previousWeights.clone();
        double[] updatedBiases = previousBiases.clone();
        double[] updatedExponents = previousExponents.clone();

        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            updatedWeights[i] -= BotConfig.Learning.WEIGHT_LEARNING_RATE
                    * clipGradient(weightGradients[i], BotConfig.Learning.WEIGHT_GRADIENT_CLIP);
            updatedWeights[i] += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                    * (segment.defaultModel.contextDistanceWeights[i] - updatedWeights[i]);
            if (updatedWeights[i] < WEIGHT_SOFT_FLOOR) {
                updatedWeights[i] = WEIGHT_SOFT_FLOOR;
            }

            updatedBiases[i] -= BotConfig.Learning.BIAS_LEARNING_RATE
                    * clipGradient(biasGradients[i], BotConfig.Learning.WEIGHT_GRADIENT_CLIP);
            updatedBiases[i] += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                    * (segment.defaultModel.featureBiases[i] - updatedBiases[i]);
            updatedBiases[i] = clamp(updatedBiases[i], MIN_FEATURE_BIAS, MAX_FEATURE_BIAS);

            updatedExponents[i] -= BotConfig.Learning.EXPONENT_LEARNING_RATE
                    * clipGradient(exponentGradients[i], BotConfig.Learning.WEIGHT_GRADIENT_CLIP);
            updatedExponents[i] += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                    * (segment.defaultModel.featureExponents[i] - updatedExponents[i]);
            updatedExponents[i] = clamp(updatedExponents[i], MIN_FEATURE_EXPONENT, MAX_FEATURE_EXPONENT);
        }
        if (!normalizeWeightMass(updatedWeights, segment.defaultWeightMass)) {
            System.arraycopy(previousWeights, 0, updatedWeights, 0, CONTEXT_DIMENSIONS);
        }

        double updatedLogBandwidth = Math.log(segment.kdeBandwidth);
        updatedLogBandwidth -= BotConfig.Learning.LOG_PARAMETER_LEARNING_RATE
                * clipGradient(logBandwidthGradient, BotConfig.Learning.LOG_PARAMETER_GRADIENT_CLIP);
        updatedLogBandwidth += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                * (Math.log(segment.defaultModel.kdeBandwidth) - updatedLogBandwidth);
        double updatedBandwidth = clamp(
                Math.exp(updatedLogBandwidth),
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);

        double updatedLogSigma = Math.log(segment.contextWeightSigma);
        updatedLogSigma -= BotConfig.Learning.LOG_PARAMETER_LEARNING_RATE
                * clipGradient(logSigmaGradient, BotConfig.Learning.LOG_PARAMETER_GRADIENT_CLIP);
        updatedLogSigma += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                * (Math.log(segment.defaultModel.contextWeightSigma) - updatedLogSigma);
        double updatedSigma = clamp(
                Math.exp(updatedLogSigma),
                BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);

        segment.applyModel(
                updatedWeights,
                updatedBandwidth,
                updatedSigma,
                updatedBiases,
                updatedExponents);

        if (!nearlyEqual(previousBandwidth, segment.kdeBandwidth)
                || !nearlyEqual(previousSigma, segment.contextWeightSigma)) {
            return true;
        }
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            if (!nearlyEqual(previousWeights[i], segment.contextDistanceWeights[i])
                    || !nearlyEqual(previousBiases[i], segment.featureBiases[i])
                    || !nearlyEqual(previousExponents[i], segment.featureExponents[i])) {
                return true;
            }
        }
        return false;
    }

    private static double finiteDifferenceLogBandwidthGradient(SegmentLog segment,
                                                               NeighborSelection selection,
                                                               double observationGfMin,
                                                               double observationGfMax,
                                                               double currentSignalSum,
                                                               double currentTotalSum) {
        double plusBandwidth = clamp(
                segment.kdeBandwidth * Math.exp(BANDWIDTH_FINITE_DIFFERENCE_LOG_DELTA),
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);
        double minusBandwidth = clamp(
                segment.kdeBandwidth * Math.exp(-BANDWIDTH_FINITE_DIFFERENCE_LOG_DELTA),
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);
        if (!(plusBandwidth > 0.0) || !(minusBandwidth > 0.0) || nearlyEqual(plusBandwidth, minusBandwidth)) {
            return 0.0;
        }
        double logDelta = Math.log(plusBandwidth) - Math.log(minusBandwidth);
        if (!(Math.abs(logDelta) > MODEL_EPSILON)) {
            return 0.0;
        }
        KernelEvaluation plusEvaluation = evaluateKernels(
                segment,
                selection,
                observationGfMin,
                observationGfMax,
                plusBandwidth);
        KernelEvaluation minusEvaluation = evaluateKernels(
                segment,
                selection,
                observationGfMin,
                observationGfMax,
                minusBandwidth);
        double signalDerivative = (plusEvaluation.signalSum - minusEvaluation.signalSum) / logDelta;
        double totalDerivative = (plusEvaluation.totalSum - minusEvaluation.totalSum) / logDelta;
        return totalDerivative / Math.max(PROBABILITY_EPSILON, currentTotalSum)
                - signalDerivative / Math.max(PROBABILITY_EPSILON, currentSignalSum);
    }

    private static KernelEvaluation evaluateKernels(SegmentLog segment,
                                                    NeighborSelection selection,
                                                    double observationGfMin,
                                                    double observationGfMax,
                                                    double bandwidth) {
        double[] signalKernels = new double[selection.candidatePool.length];
        double[] totalKernels = new double[selection.candidatePool.length];
        double signalSum = 0.0;
        double totalSum = 0.0;
        for (int i = 0; i < selection.candidatePool.length; i++) {
            DataPoint candidate = selection.candidatePool[i];
            double signalKernel = observationKernel(
                    segment,
                    candidate,
                    observationGfMin,
                    observationGfMax,
                    bandwidth);
            double totalKernel = totalKernelMass(segment, candidate, bandwidth);
            signalKernels[i] = signalKernel;
            totalKernels[i] = totalKernel;
            signalSum += selection.weights[i] * signalKernel;
            totalSum += selection.weights[i] * totalKernel;
        }
        return new KernelEvaluation(signalKernels, totalKernels, signalSum, totalSum);
    }

    private static double observationKernel(SegmentLog segment,
                                            DataPoint candidate,
                                            double observationGfMin,
                                            double observationGfMax,
                                            double bandwidth) {
        if (segment.intervalSamples) {
            return intervalKernelIntegral(
                    observationGfMin,
                    observationGfMax,
                    candidate.gfMin,
                    candidate.gfMax,
                    bandwidth);
        }
        double clampedMin = Math.max(MIN_GF, observationGfMin);
        double clampedMax = Math.min(MAX_GF, observationGfMax);
        if (clampedMax - clampedMin > MIN_INTERVAL_WIDTH) {
            return MathUtils.gaussianIntegral(clampedMin, clampedMax, candidate.gfMin, bandwidth);
        }
        return MathUtils.gaussian(observationGfMin, candidate.gfMin, bandwidth);
    }

    private static double totalKernelMass(SegmentLog segment,
                                          DataPoint candidate,
                                          double bandwidth) {
        if (segment.intervalSamples) {
            return intervalKernelIntegral(MIN_GF, MAX_GF, candidate.gfMin, candidate.gfMax, bandwidth);
        }
        return MathUtils.gaussianIntegral(MIN_GF, MAX_GF, candidate.gfMin, bandwidth);
    }

    private static int safeTreeSize(SegmentLog segment) {
        try {
            return segment.log.size();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static void addTrace(String stage, String segment, String source, int size) {
        Thread thread = Thread.currentThread();
        synchronized (TRACE_LOCK) {
            TraceEntry entry = new TraceEntry(
                    ++traceSequence,
                    stage,
                    segment,
                    source,
                    thread.getId(),
                    thread.getName(),
                    size);
            traceBuffer[traceWriteIndex] = entry;
            traceWriteIndex = (traceWriteIndex + 1) % TRACE_BUFFER_CAPACITY;
            if (traceCount < TRACE_BUFFER_CAPACITY) {
                traceCount++;
            }
        }
    }

    private static void dumpTraceOnFailure(String segment,
                                           String source,
                                           int sizeBefore,
                                           Throwable failure) {
        System.out.println("[WAVELOG-TRACE] addPoint failure"
                + " segment=" + segment
                + " source=" + source
                + " sizeBefore=" + sizeBefore
                + " failure=" + failure.getClass().getSimpleName()
                + (failure.getMessage() != null ? ": " + failure.getMessage() : ""));
        TraceEntry[] snapshot;
        synchronized (TRACE_LOCK) {
            int dumpCount = Math.min(traceCount, TRACE_DUMP_LINES_ON_FAILURE);
            snapshot = new TraceEntry[dumpCount];
            int start = (traceWriteIndex - dumpCount + TRACE_BUFFER_CAPACITY) % TRACE_BUFFER_CAPACITY;
            for (int i = 0; i < dumpCount; i++) {
                snapshot[i] = traceBuffer[(start + i) % TRACE_BUFFER_CAPACITY];
            }
        }
        for (TraceEntry entry : snapshot) {
            if (entry == null) {
                continue;
            }
            System.out.println("[WAVELOG-TRACE] #"
                    + entry.seq
                    + " stage=" + entry.stage
                    + " segment=" + entry.segment
                    + " source=" + entry.source
                    + " thread=" + entry.threadId + "/" + entry.threadName
                    + " size=" + entry.size);
        }
    }

    private static String inferLogSource() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            String method = frame.getMethodName();
            if ("oog.mega.saguaro.info.wave.WaveManager".equals(className)) {
                if ("logMyWaveResults".equals(method)) {
                    return "myWaveReach";
                }
                if ("logEnemyWaveMovementResultForBearing".equals(method)) {
                    return "enemyWaveHit";
                }
                if ("logEnemyWaveMovementPassResult".equals(method)) {
                    return "enemyWavePass";
                }
                return "WaveManager." + method;
            }
        }
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (!className.equals(WaveLog.class.getName())) {
                return className + "." + frame.getMethodName();
            }
        }
        return "unknown";
    }

    private static GuessFactorDistribution createDistribution(SegmentLog segment,
                                                              WaveContextFeatures.WaveContext context,
                                                              Boolean didHitOverride) {
        double[] normalizedQueryPoint = createNormalizedContextPoint(context, didHitOverride);
        NeighborSelection selection = selectNeighbors(
                segment,
                createEmbeddedContextPoint(segment, normalizedQueryPoint),
                candidateCountForLogSize(segment.log.size()));
        if (selection == null) {
            return null;
        }
        GuessFactorDistribution distribution = segment.intervalSamples
                ? new IntervalKDEDistribution(
                        selection.gfMins,
                        selection.gfMaxs,
                        selection.weights,
                        segment.kdeBandwidth)
                : new KDEDistribution(selection.gfMins, selection.weights, segment.kdeBandwidth);
        return shouldMirrorDistribution(context.momentumDirectionSign)
                ? new MirroredGuessFactorDistribution(distribution)
                : distribution;
    }

    private static int candidateCountForLogSize(int logSize) {
        if (logSize <= 0) {
            return 0;
        }
        return Math.min(MODEL_CANDIDATE_POOL, logSize);
    }

    private static int gradientCandidateCountForLogSize(int logSize) {
        if (logSize <= 0) {
            return 0;
        }
        return Math.min(GRADIENT_CANDIDATE_POOL, Math.max(MODEL_CANDIDATE_POOL, logSize / 5));
    }

    private static NeighborSelection selectNeighbors(SegmentLog segment,
                                                     double[] queryPoint,
                                                     int candidateCount) {
        DataPoint[] candidatePool = collectLocalCandidatePool(
                segment.log,
                queryPoint,
                candidateCount,
                segment.distanceFunction);
        if (candidatePool.length == 0) {
            return null;
        }
        double[] gfMins = new double[candidatePool.length];
        double[] gfMaxs = new double[candidatePool.length];
        double[] weights = new double[candidatePool.length];
        for (int i = 0; i < candidatePool.length; i++) {
            gfMins[i] = candidatePool[i].gfMin;
            gfMaxs[i] = candidatePool[i].gfMax;
            weights[i] = contextWeight(
                    weightedSquaredDistance(
                            candidatePool[i].contextPoint,
                            queryPoint,
                            segment.contextDistanceWeights),
                    segment.contextWeightSigma);
        }
        return new NeighborSelection(candidatePool, gfMins, gfMaxs, weights);
    }

    private static DataPoint[] collectLocalCandidatePool(KdTree<DataPoint> log,
                                                         double[] queryPoint,
                                                         int maxCount,
                                                         WeightedSquareEuclideanDistanceFunction distanceFunction) {
        int size = log.size();
        if (size == 0 || maxCount <= 0) {
            return new DataPoint[0];
        }

        int requestedCount = Math.min(size, maxCount);
        MaxHeap<DataPoint> neighbors = log.findNearestNeighbors(
                queryPoint,
                requestedCount,
                distanceFunction);
        DataPoint[] candidates = new DataPoint[requestedCount];
        int count = 0;
        while (neighbors.size() > 0) {
            candidates[count++] = neighbors.getMax();
            neighbors.removeMax();
        }

        DataPoint[] trimmed = new DataPoint[count];
        System.arraycopy(candidates, 0, trimmed, 0, count);
        return trimmed;
    }

    private static double weightedSquaredDistance(double[] point,
                                                  double[] queryPoint,
                                                  double[] weights) {
        double distance = 0.0;
        for (int i = 0; i < weights.length; i++) {
            double diff = point[i] - queryPoint[i];
            distance += weights[i] * diff * diff;
        }
        return distance;
    }

    private static double contextWeight(double squaredDistance, double sigma) {
        return Math.exp(-0.5 * squaredDistance / (sigma * sigma));
    }

    private static double intervalKernelIntegral(double gfStart,
                                                 double gfEnd,
                                                 double lowerBound,
                                                 double upperBound,
                                                 double bandwidth) {
        double clampedStart = Math.max(MIN_GF, gfStart);
        double clampedEnd = Math.min(MAX_GF, gfEnd);
        if (clampedStart >= clampedEnd) {
            return 0.0;
        }
        double intervalWidth = upperBound - lowerBound;
        if (intervalWidth <= MIN_INTERVAL_WIDTH) {
            return MathUtils.gaussianIntegral(clampedStart, clampedEnd, lowerBound, bandwidth);
        }
        double endMass = normalCdfPrimitive(clampedEnd, upperBound, bandwidth)
                - normalCdfPrimitive(clampedEnd, lowerBound, bandwidth);
        double startMass = normalCdfPrimitive(clampedStart, upperBound, bandwidth)
                - normalCdfPrimitive(clampedStart, lowerBound, bandwidth);
        return (endMass - startMass) / intervalWidth;
    }

    private static double normalCdfPrimitive(double x, double mean, double bandwidth) {
        double delta = x - mean;
        double z = delta / bandwidth;
        double cdf = MathUtils.normalCdf(z);
        double standardPdf = standardNormalPdf(z);
        return -delta * cdf - bandwidth * standardPdf;
    }

    private static double standardNormalPdf(double z) {
        return Math.exp(-0.5 * z * z) / SQRT_2PI;
    }

    private static double[] createNormalizedContextPoint(WaveContextFeatures.WaveContext context) {
        return createNormalizedContextPoint(context, null);
    }

    private static double[] createNormalizedContextPoint(WaveContextFeatures.WaveContext context,
                                                         Boolean didHitOverride) {
        validateContext(context);
        double[] normalized = new double[CONTEXT_DIMENSIONS];
        normalized[FEATURE_POWER] = normalizePower(context.bulletSpeed);
        normalized[FEATURE_BFT] = normalizeFlightTicks(context.flightTicks);
        normalized[FEATURE_ACCEL] = normalizeVelocityDelta(context.targetVelocityDelta);
        normalized[FEATURE_VEL_ABS] = normalizeAbsoluteVelocity(context.absoluteVelocity);
        normalized[FEATURE_VEL_MAX] = normalizeBoolean(context.absoluteVelocity > 7.9);
        normalized[FEATURE_LAT_VEL] = normalizeAbsoluteVelocity(context.lateralVelocity);
        normalized[FEATURE_ADV_VEL] = normalizeSignedVelocity(context.advancingVelocity);
        normalized[FEATURE_REVERSAL] = normalizeRelativeTicks(
                context.ticksSinceVelocityReversal,
                context.flightTicks);
        normalized[FEATURE_DECEL] = normalizeRelativeTicks(context.ticksSinceDecel, context.flightTicks);
        normalized[FEATURE_DIST10] = normalizeDistance(context.distanceLast10, DISTANCE_LAST_10_SCALE);
        normalized[FEATURE_DIST20] = normalizeDistance(context.distanceLast20, DISTANCE_LAST_20_SCALE);
        normalized[FEATURE_RELATIVE_HEADING] = normalizeRelativeHeading(context.relativeHeading);
        normalized[FEATURE_MAE_WALL_AHEAD] = context.wallAhead;
        normalized[FEATURE_MAE_WALL_REVERSE] = context.wallReverse;
        normalized[FEATURE_STICK_WALL_AHEAD] = context.stickWallAhead;
        normalized[FEATURE_STICK_WALL_REVERSE] = context.stickWallReverse;
        normalized[FEATURE_STICK_WALL_AHEAD2] = context.stickWallAhead2;
        normalized[FEATURE_STICK_WALL_REVERSE2] = context.stickWallReverse2;
        normalized[FEATURE_CURRENT_GF] = normalizeGuessFactor(
                canonicalizeGuessFactor(context.currentGF, context.momentumDirectionSign));
        normalized[FEATURE_DID_HIT] = normalizeBoolean(
                didHitOverride != null ? didHitOverride.booleanValue() : context.didHit);
        normalized[FEATURE_BATTLE_TIME] = normalizeBattleTime(context.battleTime);
        normalized[FEATURE_SHOTS_FIRED] = normalizeShotsFired(context.shotsFired);
        return normalized;
    }

    private static double[] createEmbeddedContextPoint(SegmentLog segment,
                                                       double[] normalizedContextPoint) {
        if (segment == null) {
            throw new IllegalArgumentException("Wave-log segment must be non-null");
        }
        if (normalizedContextPoint == null || normalizedContextPoint.length != CONTEXT_DIMENSIONS) {
            throw new IllegalArgumentException("Wave-log normalized context must match context dimension count");
        }
        double[] transformed = new double[CONTEXT_DIMENSIONS];
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            transformed[i] = transformFeature(
                    normalizedContextPoint[i],
                    segment.featureBiases[i],
                    segment.featureExponents[i]);
        }
        return transformed;
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Wave-log context must be non-null");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Wave-log point requires positive finite bullet speed");
        }
        if (!Double.isFinite(context.lateralVelocity)) {
            throw new IllegalArgumentException("Wave-log point requires finite lateral velocity");
        }
        if (!Double.isFinite(context.advancingVelocity)) {
            throw new IllegalArgumentException("Wave-log point requires finite advancing velocity");
        }
        if (!Double.isFinite(context.targetVelocityDelta)) {
            throw new IllegalArgumentException("Wave-log point requires finite velocity delta");
        }
        if (!Double.isFinite(context.absoluteVelocity)) {
            throw new IllegalArgumentException("Wave-log point requires finite absolute velocity");
        }
        if (!Double.isFinite(context.relativeHeading)) {
            throw new IllegalArgumentException("Wave-log point requires finite relative heading");
        }
        if (!Double.isFinite(context.distanceLast10) || !Double.isFinite(context.distanceLast20)) {
            throw new IllegalArgumentException("Wave-log point requires finite rolling distance features");
        }
        if (!Double.isFinite(context.wallAhead) || !Double.isFinite(context.wallReverse)) {
            throw new IllegalArgumentException("Wave-log point requires finite wall features");
        }
        if (!Double.isFinite(context.stickWallAhead)
                || !Double.isFinite(context.stickWallReverse)
                || !Double.isFinite(context.stickWallAhead2)
                || !Double.isFinite(context.stickWallReverse2)) {
            throw new IllegalArgumentException("Wave-log point requires finite stick-wall features");
        }
        if (!Double.isFinite(context.currentGF)) {
            throw new IllegalArgumentException("Wave-log point requires finite current GF");
        }
        if (!Double.isFinite(context.momentumLateralVelocity)) {
            throw new IllegalArgumentException("Wave-log point requires finite momentum lateral velocity");
        }
        if (context.flightTicks <= 0) {
            throw new IllegalArgumentException("Wave-log point requires positive flight ticks");
        }
        if (context.ticksSinceVelocityReversal < 0 || context.ticksSinceDecel < 0) {
            throw new IllegalArgumentException("Wave-log point requires non-negative history counters");
        }
        if (context.battleTime < 0L) {
            throw new IllegalArgumentException("Wave-log point requires non-negative battle time");
        }
        if (context.shotsFired < 0) {
            throw new IllegalArgumentException("Wave-log point requires non-negative shots fired");
        }
        if (WaveContextFeatures.normalizeDirectionSign(context.momentumDirectionSign) == 0) {
            throw new IllegalArgumentException("Wave-log point requires a non-zero momentum direction sign");
        }
    }

    private static double normalizePower(double bulletSpeed) {
        return ((20.0 - bulletSpeed) / 3.0) / 3.0;
    }

    private static double normalizeAbsoluteVelocity(double velocity) {
        return Math.abs(velocity) / MAX_LATERAL_VELOCITY;
    }

    private static double normalizeSignedVelocity(double velocity) {
        return (velocity + MAX_LATERAL_VELOCITY) / (2.0 * MAX_LATERAL_VELOCITY);
    }

    private static double normalizeFlightTicks(int flightTicks) {
        return flightTicks / FLIGHT_TICKS_SCALE;
    }

    private static double normalizeVelocityDelta(double velocityDelta) {
        return (velocityDelta - MIN_VELOCITY_DELTA) / (MAX_VELOCITY_DELTA - MIN_VELOCITY_DELTA);
    }

    private static double normalizeRelativeTicks(int ticks, int flightTicks) {
        return Math.min(RELATIVE_TICKS_CAP, ticks) / Math.max(1.0, flightTicks);
    }

    private static double normalizeDistance(double distance, double scale) {
        return distance / scale;
    }

    private static double normalizeRelativeHeading(double relativeHeading) {
        return Math.abs(relativeHeading) / Math.PI;
    }

    private static double normalizeBoolean(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static double normalizeBattleTime(long battleTime) {
        return battleTime / BATTLE_TIME_SCALE;
    }

    private static double normalizeShotsFired(int shotsFired) {
        return shotsFired / SHOTS_FIRED_SCALE;
    }

    private static double transformFeature(double value, double bias, double exponent) {
        double base = Math.max(FEATURE_TRANSFORM_EPSILON, FEATURE_TRANSFORM_EPSILON + bias + value);
        return Math.pow(base, exponent);
    }

    private static double transformFeatureDerivativeBias(double value, double bias, double exponent) {
        double base = Math.max(FEATURE_TRANSFORM_EPSILON, FEATURE_TRANSFORM_EPSILON + bias + value);
        return exponent * Math.pow(base, exponent - 1.0);
    }

    private static double transformFeatureDerivativeExponent(double value, double bias, double exponent) {
        double transformed = transformFeature(value, bias, exponent);
        double base = Math.max(FEATURE_TRANSFORM_EPSILON, FEATURE_TRANSFORM_EPSILON + bias + value);
        return transformed * Math.log(base);
    }

    private static double normalizeGuessFactor(double guessFactor) {
        return (guessFactor + 1.0) * 0.5;
    }

    private static double canonicalizeGuessFactor(double guessFactor, int lateralDirectionSign) {
        return shouldMirrorDistribution(lateralDirectionSign) ? -guessFactor : guessFactor;
    }

    private static double[] canonicalizeGuessFactorRange(double gfMin, double gfMax, int lateralDirectionSign) {
        if (!shouldMirrorDistribution(lateralDirectionSign)) {
            return new double[]{gfMin, gfMax};
        }
        return new double[]{-gfMax, -gfMin};
    }

    private static boolean shouldMirrorDistribution(int lateralDirectionSign) {
        return WaveContextFeatures.normalizeDirectionSign(lateralDirectionSign) < 0;
    }

    private static double clipGradient(double value, double clip) {
        return clamp(value, -clip, clip);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int quantizeLinear(double value, double min, double max) {
        if (!(max > min)) {
            return 0;
        }
        double clamped = clamp(Double.isFinite(value) ? value : min, min, max);
        double normalized = (clamped - min) / (max - min);
        return (int) Math.round(normalized * PERSISTED_UNSIGNED_SHORT_MAX);
    }

    private static double dequantizeLinear(int quantized, double min, double max) {
        if (!(max > min)) {
            return min;
        }
        double clamped = clamp(quantized, 0.0, PERSISTED_UNSIGNED_SHORT_MAX);
        return min + (max - min) * (clamped / PERSISTED_UNSIGNED_SHORT_MAX);
    }

    private static int quantizeLogScaled(double value, double min, double max) {
        double safeMin = Math.max(MODEL_EPSILON, min);
        double safeMax = Math.max(safeMin, max);
        double clamped = clamp(Double.isFinite(value) ? value : safeMin, safeMin, safeMax);
        return quantizeLinear(Math.log(clamped), Math.log(safeMin), Math.log(safeMax));
    }

    private static double dequantizeLogScaled(int quantized, double min, double max) {
        double safeMin = Math.max(MODEL_EPSILON, min);
        double safeMax = Math.max(safeMin, max);
        return Math.exp(dequantizeLinear(quantized, Math.log(safeMin), Math.log(safeMax)));
    }

    private static int quantizeWeight(double value) {
        return quantizeLogScaled(value, MIN_PERSISTED_WEIGHT, MAX_PERSISTED_WEIGHT);
    }

    private static double dequantizeWeight(int quantized) {
        return dequantizeLogScaled(quantized, MIN_PERSISTED_WEIGHT, MAX_PERSISTED_WEIGHT);
    }

    public static void startBattlePersistence() {
        persistedSectionLoaded = false;
        currentBattleModelUpdated = false;
        persistedTargetingModel = null;
        persistedMovementModel = null;
        gunSegment.resetModelToDefault();
        movementSegment.resetModelToDefault();
    }

    public static void startBattle() {
        currentBattleModelUpdated = false;
        gunSegment.resetBattleState();
        movementSegment.resetBattleState();
    }

    public static int persistenceSectionVersion() {
        return PERSISTENCE_SECTION_VERSION;
    }

    public static void loadPersistedSection(int sectionVersion, byte[] payload) {
        if (payload == null) {
            return;
        }
        if (sectionVersion != PERSISTENCE_SECTION_VERSION) {
            throw new IllegalStateException("Unsupported wave-model section version " + sectionVersion);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int componentMask = in.readUnsignedByte();
            if ((componentMask & ~(PERSISTED_TARGETING_MODEL_MASK | PERSISTED_MOVEMENT_MODEL_MASK)) != 0
                    || componentMask == 0) {
                throw new IllegalStateException("Wave-model payload contained an invalid component mask");
            }
            int persistedModelCount = Integer.bitCount(componentMask);
            int expectedPayloadBytes =
                    PERSISTED_MODEL_COMPONENT_MASK_BYTES + persistedModelCount * PERSISTED_MODEL_BYTES;
            if (payload.length != expectedPayloadBytes) {
                throw new IllegalStateException(
                        "Unexpected wave-model payload length: expected "
                                + expectedPayloadBytes + " but was " + payload.length);
            }
            boolean hasTargetingModel = (componentMask & PERSISTED_TARGETING_MODEL_MASK) != 0;
            boolean hasMovementModel = (componentMask & PERSISTED_MOVEMENT_MODEL_MASK) != 0;
            ModelSpec targetingModel = hasTargetingModel
                    ? readPersistedModel(in, DEFAULT_TARGETING_MODEL)
                    : DEFAULT_TARGETING_MODEL;
            ModelSpec movementModel = hasMovementModel
                    ? readPersistedModel(in, DEFAULT_MOVEMENT_MODEL)
                    : DEFAULT_MOVEMENT_MODEL;
            if (in.available() != 0) {
                throw new IllegalStateException("Wave-model payload contained trailing bytes");
            }
            applyPersistedModels(targetingModel, hasTargetingModel, movementModel, hasMovementModel);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable wave-model payload", e);
        }
    }

    public static boolean hasPersistedSectionData() {
        return persistedSectionLoaded || currentBattleModelUpdated;
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes,
                                                       boolean includeCurrentBattleData) {
        return createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData, true, true);
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes,
                                                       boolean includeCurrentBattleData,
                                                       boolean includeTargetingModel,
                                                       boolean includeMovementModel) {
        ModelSpec targetingModel = includeTargetingModel
                ? modelForPersistence(includeCurrentBattleData, true)
                : null;
        ModelSpec movementModel = includeMovementModel
                ? modelForPersistence(includeCurrentBattleData, false)
                : null;
        boolean hasTargetingModel = targetingModel != null;
        boolean hasMovementModel = movementModel != null;
        if (!hasTargetingModel && !hasMovementModel) {
            return null;
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            int componentMask = 0;
            if (hasTargetingModel) {
                componentMask |= PERSISTED_TARGETING_MODEL_MASK;
            }
            if (hasMovementModel) {
                componentMask |= PERSISTED_MOVEMENT_MODEL_MASK;
            }
            out.writeByte(componentMask);
            if (hasTargetingModel) {
                writePersistedModel(out, targetingModel);
            }
            if (hasMovementModel) {
                writePersistedModel(out, movementModel);
            }
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length > maxPayloadBytes) {
                throw new IllegalStateException(
                        "Insufficient data quota to save wave-model payload: required="
                                + payload.length + " bytes, available=" + maxPayloadBytes + " bytes");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build wave-model payload", e);
        }
    }

    private static ModelSpec modelForPersistence(boolean includeCurrentBattleData, boolean targetingModel) {
        SegmentLog segment = targetingModel ? gunSegment : movementSegment;
        ModelSpec defaultModel = targetingModel ? DEFAULT_TARGETING_MODEL : DEFAULT_MOVEMENT_MODEL;
        ModelSpec persistedModel = targetingModel ? persistedTargetingModel : persistedMovementModel;
        if (includeCurrentBattleData) {
            if (!persistedSectionLoaded && !currentBattleModelUpdated) {
                return null;
            }
            if (persistedModel == null && isDefaultModel(segment, defaultModel)) {
                return null;
            }
            return copyCurrentModel(segment);
        }
        return persistedModel;
    }

    private static void applyPersistedModels(ModelSpec targetingModel,
                                             boolean hasTargetingModel,
                                             ModelSpec movementModel,
                                             boolean hasMovementModel) {
        persistedTargetingModel = hasTargetingModel ? targetingModel : null;
        persistedMovementModel = hasMovementModel ? movementModel : null;
        persistedSectionLoaded = true;
        gunSegment.applyModel(hasTargetingModel ? targetingModel : DEFAULT_TARGETING_MODEL);
        movementSegment.applyModel(hasMovementModel ? movementModel : DEFAULT_MOVEMENT_MODEL);
        if (!gunSegment.currentBattleSamples.isEmpty()) {
            gunSegment.rebuildIndex();
        }
        if (!movementSegment.currentBattleSamples.isEmpty()) {
            movementSegment.rebuildIndex();
        }
    }

    private static void writePersistedModel(DataOutputStream out,
                                            ModelSpec model) throws IOException {
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            out.writeShort(quantizeWeight(model.contextDistanceWeights[i]));
        }
        out.writeShort(quantizeLogScaled(
                model.kdeBandwidth,
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH));
        out.writeShort(quantizeLogScaled(
                model.contextWeightSigma,
                BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA));
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            out.writeShort(quantizeLinear(model.featureBiases[i], MIN_FEATURE_BIAS, MAX_FEATURE_BIAS));
        }
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            out.writeShort(quantizeLinear(
                    model.featureExponents[i],
                    MIN_FEATURE_EXPONENT,
                    MAX_FEATURE_EXPONENT));
        }
    }

    private static ModelSpec readPersistedModel(DataInputStream in,
                                                ModelSpec defaultModel) throws IOException {
        double[] weights = new double[CONTEXT_DIMENSIONS];
        double[] biases = new double[CONTEXT_DIMENSIONS];
        double[] exponents = new double[CONTEXT_DIMENSIONS];
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            weights[i] = dequantizeWeight(in.readUnsignedShort());
        }
        double bandwidth = dequantizeLogScaled(
                in.readUnsignedShort(),
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);
        double sigma = dequantizeLogScaled(
                in.readUnsignedShort(),
                BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            biases[i] = dequantizeLinear(in.readUnsignedShort(), MIN_FEATURE_BIAS, MAX_FEATURE_BIAS);
        }
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            exponents[i] = dequantizeLinear(
                    in.readUnsignedShort(),
                    MIN_FEATURE_EXPONENT,
                    MAX_FEATURE_EXPONENT);
        }
        return sanitizeModel(defaultModel, weights, bandwidth, sigma, biases, exponents);
    }

    private static ModelSpec sanitizeModel(ModelSpec defaultModel,
                                           double[] weights,
                                           double kdeBandwidth,
                                           double contextWeightSigma,
                                           double[] biases,
                                           double[] exponents) {
        double[] sanitizedWeights = defaultModel.contextDistanceWeights.clone();
        if (weights != null && weights.length == CONTEXT_DIMENSIONS) {
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                double weight = weights[i];
                sanitizedWeights[i] = Double.isFinite(weight) && weight > 0.0 ? weight : 0.0;
            }
            if (!normalizeWeightMass(sanitizedWeights, sumPositiveWeights(defaultModel.contextDistanceWeights))) {
                System.arraycopy(defaultModel.contextDistanceWeights, 0, sanitizedWeights, 0, CONTEXT_DIMENSIONS);
            }
        }

        double[] sanitizedBiases = defaultModel.featureBiases.clone();
        if (biases != null && biases.length == CONTEXT_DIMENSIONS) {
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                sanitizedBiases[i] = clamp(
                        Double.isFinite(biases[i]) ? biases[i] : defaultModel.featureBiases[i],
                        MIN_FEATURE_BIAS,
                        MAX_FEATURE_BIAS);
            }
        }

        double[] sanitizedExponents = defaultModel.featureExponents.clone();
        if (exponents != null && exponents.length == CONTEXT_DIMENSIONS) {
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                sanitizedExponents[i] = clamp(
                        Double.isFinite(exponents[i]) ? exponents[i] : defaultModel.featureExponents[i],
                        MIN_FEATURE_EXPONENT,
                        MAX_FEATURE_EXPONENT);
            }
        }

        double sanitizedBandwidth = clamp(
                Double.isFinite(kdeBandwidth) ? kdeBandwidth : defaultModel.kdeBandwidth,
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);
        double sanitizedSigma = clamp(
                Double.isFinite(contextWeightSigma) ? contextWeightSigma : defaultModel.contextWeightSigma,
                BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);

        return new ModelSpec(
                sanitizedWeights,
                sanitizedBandwidth,
                sanitizedSigma,
                sanitizedBiases,
                sanitizedExponents);
    }

    private static ModelSpec copyCurrentModel(SegmentLog segment) {
        return new ModelSpec(
                segment.contextDistanceWeights,
                segment.kdeBandwidth,
                segment.contextWeightSigma,
                segment.featureBiases,
                segment.featureExponents);
    }

    public static String getTargetingModelName() {
        return describeModel(gunSegment, DEFAULT_TARGETING_MODEL);
    }

    public static String getTargetingModelSummary() {
        return describeModelSummary(gunSegment, DEFAULT_TARGETING_MODEL);
    }

    public static List<String> getTargetingModelDeltaLines() {
        return describeModelDelta(gunSegment, DEFAULT_TARGETING_MODEL);
    }

    public static List<String> getTargetingModelAbsoluteLines() {
        return describeModelAbsolute(gunSegment);
    }

    public static String getDefaultTargetingModelSummary() {
        return describeModel(DEFAULT_TARGETING_MODEL);
    }

    public static int getTargetingSampleCount() {
        return gunSegment.log.size();
    }

    public static String getMovementModelName() {
        return describeModel(movementSegment, DEFAULT_MOVEMENT_MODEL);
    }

    public static String getMovementModelSummary() {
        return describeModelSummary(movementSegment, DEFAULT_MOVEMENT_MODEL);
    }

    public static List<String> getMovementModelDeltaLines() {
        return describeModelDelta(movementSegment, DEFAULT_MOVEMENT_MODEL);
    }

    public static List<String> getMovementModelAbsoluteLines() {
        return describeModelAbsolute(movementSegment);
    }

    public static String getDefaultMovementModelSummary() {
        return describeModel(DEFAULT_MOVEMENT_MODEL);
    }

    public static boolean isTargetingModelDefault() {
        return isDefaultModel(gunSegment, DEFAULT_TARGETING_MODEL);
    }

    public static boolean isMovementModelDefault() {
        return isDefaultModel(movementSegment, DEFAULT_MOVEMENT_MODEL);
    }

    private static boolean isDefaultModel(SegmentLog segment, ModelSpec defaultModel) {
        if (!nearlyEqual(segment.kdeBandwidth, defaultModel.kdeBandwidth)
                || !nearlyEqual(segment.contextWeightSigma, defaultModel.contextWeightSigma)) {
            return false;
        }
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            if (!nearlyEqual(segment.contextDistanceWeights[i], defaultModel.contextDistanceWeights[i])
                    || !nearlyEqual(segment.featureBiases[i], defaultModel.featureBiases[i])
                    || !nearlyEqual(segment.featureExponents[i], defaultModel.featureExponents[i])) {
                return false;
            }
        }
        return true;
    }

    private static String describeModelSummary(SegmentLog segment, ModelSpec defaultModel) {
        return isDefaultModel(segment, defaultModel) ? "Default" : describeModel(segment, defaultModel);
    }

    private static List<String> describeModelDelta(SegmentLog segment, ModelSpec defaultModel) {
        List<String> lines = new ArrayList<>(CONTEXT_DIMENSIONS + 2);
        lines.add(String.format(
                Locale.US,
                "%-12s d=%+.4f",
                "bandwidth",
                segment.kdeBandwidth - defaultModel.kdeBandwidth));
        lines.add(String.format(
                Locale.US,
                "%-12s d=%+.4f",
                "sigma",
                segment.contextWeightSigma - defaultModel.contextWeightSigma));
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            lines.add(String.format(
                    Locale.US,
                    "%-12s dw=%+.4f db=%+.4f de=%+.4f",
                    contextLabel(i),
                    segment.contextDistanceWeights[i] - defaultModel.contextDistanceWeights[i],
                    segment.featureBiases[i] - defaultModel.featureBiases[i],
                    segment.featureExponents[i] - defaultModel.featureExponents[i]));
        }
        return lines;
    }

    private static List<String> describeModelAbsolute(SegmentLog segment) {
        List<String> lines = new ArrayList<>(CONTEXT_DIMENSIONS + 2);
        lines.add(String.format(
                Locale.US,
                "%-12s  =%.4f",
                "bandwidth",
                segment.kdeBandwidth));
        lines.add(String.format(
                Locale.US,
                "%-12s  =%.4f",
                "sigma",
                segment.contextWeightSigma));
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            lines.add(String.format(
                    Locale.US,
                    "%-12s  w=%.4f  b=%.4f  e=%.4f",
                    contextLabel(i),
                    segment.contextDistanceWeights[i],
                    segment.featureBiases[i],
                    segment.featureExponents[i]));
        }
        return lines;
    }

    private static String describeModel(ModelSpec model) {
        return describeModel(
                model.contextDistanceWeights,
                model.featureBiases,
                model.featureExponents,
                model.kdeBandwidth,
                model.contextWeightSigma,
                true,
                MODEL_CANDIDATE_POOL);
    }

    private static String describeModel(SegmentLog segment, ModelSpec defaultModel) {
        return describeModel(
                segment.contextDistanceWeights,
                segment.featureBiases,
                segment.featureExponents,
                segment.kdeBandwidth,
                segment.contextWeightSigma,
                isDefaultModel(segment, defaultModel),
                candidateCountForLogSize(segment.log.size()));
    }

    private static String describeModel(double[] weights,
                                        double[] biases,
                                        double[] exponents,
                                        double kdeBandwidth,
                                        double contextWeightSigma,
                                        boolean appendDefaultMarker,
                                        int candidateCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            double weight = weights[i];
            if (weight <= WEIGHT_ZERO_EPSILON) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(contextLabel(i)).append('=')
                    .append(String.format(Locale.US, "%.2f/%.2f/%.2f", weight, biases[i], exponents[i]));
        }
        if (builder.length() == 0) {
            builder.append("allOff");
        }
        builder.append(String.format(
                Locale.US,
                " bw=%.2f s=%.2f k=%d",
                kdeBandwidth,
                contextWeightSigma,
                candidateCount));
        if (appendDefaultMarker) {
            builder.append(" (default)");
        }
        return builder.toString();
    }

    private static String contextLabel(int index) {
        switch (index) {
        case 0:
            return "power";
        case 1:
            return "bft";
        case 2:
            return "accel";
        case 3:
            return "velAbs";
        case 4:
            return "velMax";
        case 5:
            return "latVel";
        case 6:
            return "advVel";
        case 7:
            return "reversal";
        case 8:
            return "decel";
        case 9:
            return "dist10";
        case 10:
            return "dist20";
        case 11:
            return "relHead";
        case 12:
            return "maeAhead";
        case 13:
            return "maeReverse";
        case 14:
            return "stickAhead";
        case 15:
            return "stickReverse";
        case 16:
            return "stickAhead2";
        case 17:
            return "stickReverse2";
        case 18:
            return "currentGf";
        case 19:
            return "didHit";
        case 20:
            return "battleTime";
        case 21:
            return "shotsFired";
        default:
            return "f" + index;
        }
    }

    private static boolean normalizeWeightMass(double[] weights, double targetMass) {
        double sum = sumPositiveWeights(weights);
        if (!(sum > MODEL_EPSILON) || !(targetMass > MODEL_EPSILON)) {
            return false;
        }
        double scale = targetMass / sum;
        for (int i = 0; i < weights.length; i++) {
            weights[i] = Math.max(weights[i] * scale, WEIGHT_SOFT_FLOOR);
        }
        return true;
    }

    private static double sumPositiveWeights(double[] weights) {
        double sum = 0.0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > WEIGHT_ZERO_EPSILON) {
                sum += weights[i];
            }
        }
        return sum;
    }

    private static boolean nearlyEqual(double left, double right) {
        return Math.abs(left - right) <= 1e-6;
    }
}
