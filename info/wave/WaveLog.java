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
    static final int CONTEXT_DIMENSIONS = 9;
    private static final int MODEL_CANDIDATE_POOL = 7;
    private static final int PERSISTENCE_SECTION_VERSION = 5;
    private static final int LEGACY_2_MODEL_SECTION_VERSION = 3;
    private static final int LEGACY_4_MODEL_SECTION_VERSION = 4;
    private static final int LEGACY_MODEL_SECTION_BYTES = (CONTEXT_DIMENSIONS + 2) * 2 * Short.BYTES;
    private static final double MAX_LATERAL_VELOCITY = 8.0;
    private static final double FLIGHT_TICKS_SCALE = Wave.nominalFlightTicks(
            Math.hypot(
                    800.0 - WaveContextFeatures.ROBOT_DIAMETER,
                    600.0 - WaveContextFeatures.ROBOT_DIAMETER),
            Wave.bulletSpeed(3.0));
    private static final double REVERSAL_TICKS_SCALE = 50.0;
    private static final double DECEL_TICKS_SCALE = 50.0;
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final double MIN_INTERVAL_WIDTH = 1e-9;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);
    private static final double MIN_PROBABILITY_MASS = 1e-12;
    private static final double WEIGHT_ZERO_EPSILON = 1e-6;
    private static final int MODEL_VALUE_COUNT = (CONTEXT_DIMENSIONS + 2) * 2;
    private static final int MODEL_SECTION_BYTES = MODEL_VALUE_COUNT * Short.BYTES;
    private static final double MODEL_EPSILON = 1e-9;
    private static final double PERSISTED_UINT16_MAX = 65535.0;
    private static final double[] DEFAULT_TARGETING_DISTANCE_WEIGHTS = new double[]{
            1.78602584312953,
            0.57528534102562,
            0.330866160626593,
            0.152055536772799,
            0.130674495912194,
            0.219964687136779,
            4.11943843652174,
            0.63409931315742,
            1.05159018571732
    };
    private static final double[] DEFAULT_MOVEMENT_DISTANCE_WEIGHTS = new double[]{
            7.11845214065455,
            0.124079089389588,
            0.0562788353245633,
            0.0703475880618008,
            0.0871601832920936,
            0.214193238293614,
            0.682155627389907,
            0.426460905914465,
            0.220872391679423
    };
    private static final ModelSpec DEFAULT_TARGETING_MODEL = new ModelSpec(
            "lat1p79-adv0p58-flight0p33-accel0p15-reversal0p13-decel0p22-wallAhead4p12-wallReverse0p63-currentGf1p05-bw0p36-k7-s0p40",
            DEFAULT_TARGETING_DISTANCE_WEIGHTS,
            BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH,
            BotConfig.Learning.DEFAULT_TARGETING_CONTEXT_WEIGHT_SIGMA);
    private static final ModelSpec DEFAULT_MOVEMENT_MODEL = new ModelSpec(
            "lat7p12-adv0p12-flight0p06-accel0p07-reversal0p09-decel0p21-wallAhead0p68-wallReverse0p43-currentGf0p22-bw0p36-k7-s0p55",
            DEFAULT_MOVEMENT_DISTANCE_WEIGHTS,
            BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH,
            BotConfig.Learning.DEFAULT_MOVEMENT_CONTEXT_WEIGHT_SIGMA);
    // Nearest-neighbor lookups use Rednaxela's third-gen KD-tree implementation.
    private static final SegmentLog gunSegment = new SegmentLog("targeting", DEFAULT_TARGETING_MODEL, true);
    private static final SegmentLog movementSegment = new SegmentLog("surfing", DEFAULT_MOVEMENT_MODEL, false);
    // Stack-trace source tagging was added for KD-tree diagnostics and is too expensive for normal logging.
    private static final int TRACE_BUFFER_CAPACITY = 80;
    private static final int TRACE_DUMP_LINES_ON_FAILURE = 24;
    private static final Object TRACE_LOCK = new Object();
    private static final TraceEntry[] traceBuffer = new TraceEntry[TRACE_BUFFER_CAPACITY];
    private static long traceSequence = 0L;
    private static int traceWriteIndex = 0;
    private static int traceCount = 0;
    private static String trackedOpponentName;
    private static boolean persistedModelLoaded;
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
        final double[] contextPoint;
        final double gfMin;
        final double gfMax;

        DataPoint(double[] contextPoint, double gfMin, double gfMax) {
            this.contextPoint = contextPoint;
            this.gfMin = gfMin;
            this.gfMax = gfMax;
        }
    }

    private static final class ModelSpec {
        final String name;
        final double[] contextDistanceWeights;
        final double kdeBandwidth;
        final double contextWeightSigma;

        ModelSpec(String name,
                  double[] contextDistanceWeights,
                  double kdeBandwidth,
                  double contextWeightSigma) {
            if (contextDistanceWeights == null || contextDistanceWeights.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Model weights must match context dimension count");
            }
            this.name = name;
            this.contextDistanceWeights = contextDistanceWeights.clone();
            this.kdeBandwidth = kdeBandwidth;
            this.contextWeightSigma = contextWeightSigma;
        }
    }

    private static class NeighborSelection {
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

    private static final class GradientStep {
        final double[] distanceWeightGradients;
        final double bandwidthGradient;
        final double contextSigmaGradient;

        GradientStep(double[] distanceWeightGradients,
                     double bandwidthGradient,
                     double contextSigmaGradient) {
            this.distanceWeightGradients = distanceWeightGradients;
            this.bandwidthGradient = bandwidthGradient;
            this.contextSigmaGradient = contextSigmaGradient;
        }
    }

    static final class SegmentLog {
        final String label;
        final ModelSpec defaultModel;
        final boolean intervalSamples;
        KdTree<DataPoint> log = new KdTree<DataPoint>(CONTEXT_DIMENSIONS);
        final List<DataPoint> currentBattleSamples = new ArrayList<>();
        final double[] contextDistanceWeights = new double[CONTEXT_DIMENSIONS];
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

        void setModel(ModelSpec model) {
            if (model == null) {
                throw new IllegalArgumentException("Segment model must be non-null");
            }
            applyModel(model.contextDistanceWeights, model.kdeBandwidth, model.contextWeightSigma);
        }

        void resetBattleState() {
            log = new KdTree<DataPoint>(CONTEXT_DIMENSIONS);
            currentBattleSamples.clear();
        }

        void resetModelToDefault() {
            applyModel(defaultModel.contextDistanceWeights, defaultModel.kdeBandwidth, defaultModel.contextWeightSigma);
        }

        private void applyModel(double[] weights, double kdeBandwidth, double contextWeightSigma) {
            if (weights == null || weights.length != CONTEXT_DIMENSIONS) {
                throw new IllegalArgumentException("Segment weights must match context dimension count");
            }
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                double weight = weights[i];
                contextDistanceWeights[i] = Double.isFinite(weight) && weight > 0.0 ? weight : 0.0;
            }
            normalizeWeightMass(contextDistanceWeights, defaultWeightMass);
            syncDistanceFunctionWeights();
            this.kdeBandwidth = clamp(
                    kdeBandwidth,
                    BotConfig.Learning.MIN_KDE_BANDWIDTH,
                    BotConfig.Learning.MAX_KDE_BANDWIDTH);
            this.contextWeightSigma =
                    clamp(
                            contextWeightSigma,
                            BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                            BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);
        }

        private void syncDistanceFunctionWeights() {
            for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
                kdTreeDistanceWeights[i] = Math.sqrt(Math.max(0.0, contextDistanceWeights[i]));
            }
        }
    }

    public static int persistenceSectionVersion() {
        return PERSISTENCE_SECTION_VERSION;
    }

    public static void startBattlePersistence() {
        trackedOpponentName = null;
        persistedModelLoaded = false;
        gunSegment.resetModelToDefault();
        movementSegment.resetModelToDefault();
        gunSegment.resetBattleState();
        movementSegment.resetBattleState();
    }

    public static void ensureOpponentBaselineLoaded(String opponentName, int sectionVersion, byte[] payload) {
        if (opponentName == null || opponentName.isEmpty()) {
            throw new IllegalArgumentException("WaveLog requires a non-empty opponent name");
        }
        if (trackedOpponentName == null) {
            trackedOpponentName = opponentName;
            persistedModelLoaded = false;
            gunSegment.resetModelToDefault();
            movementSegment.resetModelToDefault();
            if (payload != null) {
                loadPersistedPayload(sectionVersion, payload);
            }
            return;
        }
        if (!trackedOpponentName.equals(opponentName)) {
            throw new IllegalStateException(
                    "WaveLog expected a single opponent but saw: "
                            + trackedOpponentName + " and " + opponentName);
        }
    }

    public static boolean hasPersistedSectionData() {
        return trackedOpponentName != null
                && (persistedModelLoaded
                || !isDefaultModel(gunSegment, DEFAULT_TARGETING_MODEL)
                || !isDefaultModel(movementSegment, DEFAULT_MOVEMENT_MODEL));
    }

    public static boolean isPersistedModelLoaded() {
        return persistedModelLoaded;
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes, boolean includeCurrentBattleData) {
        if (maxPayloadBytes < MODEL_SECTION_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save WaveLog model payload: payload budget="
                            + maxPayloadBytes + " bytes");
        }
        if (!hasPersistedSectionData()) {
            return null;
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            writeModel(out, gunSegment);
            writeModel(out, movementSegment);
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length != MODEL_SECTION_BYTES) {
                throw new IllegalStateException("Unexpected WaveLog model payload length");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build WaveLog model payload", e);
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
        logGunInterval(context, gfMin, gfMax, saveObservation, true);
    }

    public static void logGunInterval(WaveContextFeatures.WaveContext context,
                                      double gfMin,
                                      double gfMax,
                                      boolean saveObservation,
                                      boolean updateModel) {
        logResult(gunSegment, context, gfMin, gfMax, saveObservation, updateModel);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context, double gf) {
        logMovementResult(context, gf, true);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation) {
        logMovementResult(context, gf, saveObservation, true);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation,
                                         boolean updateModel) {
        logResult(movementSegment, context, gf, gf, saveObservation, updateModel);
    }

    /**
     * Creates a gun distribution (how the opponent moves) for the given firing context,
     * or null if no data is available.
     */
    public static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        if (gunSegment.log.size() < BotConfig.Learning.MIN_TARGETING_SAMPLE_COUNT) {
            return null;
        }
        return createDistribution(gunSegment, context);
    }

    /**
     * Creates a movement distribution (how the opponent aims) for the given firing context,
     * or null if no data is available.
     */
    public static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        if (movementSegment.log.size() < BotConfig.Learning.MIN_MOVEMENT_SAMPLE_COUNT) {
            return null;
        }
        return createDistribution(movementSegment, context);
    }

    private static void logResult(SegmentLog segment,
                                  WaveContextFeatures.WaveContext context,
                                  double gfMin,
                                  double gfMax,
                                  boolean saveObservation,
                                  boolean updateModel) {
        if (!Double.isFinite(gfMin) || !Double.isFinite(gfMax) || gfMin > gfMax) {
            throw new IllegalArgumentException("Wave-log result requires ordered finite GF bounds");
        }
        double[] contextPoint = createContextPoint(context);
        double[] canonicalGfRange = canonicalizeGuessFactorRange(gfMin, gfMax, context.lateralDirectionSign);
        double canonicalGfMin = canonicalGfRange[0];
        double canonicalGfMax = canonicalGfRange[1];
        if (updateModel && segment.log.size() > 0) {
            updateModelFromObservation(segment, contextPoint, canonicalGfMin, canonicalGfMax);
        }
        DataPoint dataPoint = new DataPoint(contextPoint, canonicalGfMin, canonicalGfMax);
        String source = BotConfig.Debug.ENABLE_TRACE_SOURCE_CAPTURE ? inferLogSource() : "disabled";
        int sizeBefore = segment.log.size();
        addTrace("before", segment.label, source, sizeBefore);
        try {
            segment.log.addPoint(contextPoint, dataPoint);
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

    private static void updateModelFromObservation(SegmentLog segment,
                                                   double[] queryPoint,
                                                   double observedGfMin,
                                                   double observedGfMax) {
        NeighborSelection selection = selectNeighbors(segment, queryPoint, MODEL_CANDIDATE_POOL);
        if (selection == null) {
            return;
        }
        GradientStep gradient = computeGradient(segment, selection, queryPoint, observedGfMin, observedGfMax);
        if (gradient == null) {
            return;
        }
        applyGradient(segment, gradient);
    }

    private static GradientStep computeGradient(SegmentLog segment,
                                                NeighborSelection selection,
                                                double[] queryPoint,
                                                double observedGfMin,
                                                double observedGfMax) {
        double sigma = segment.contextWeightSigma;
        double sigmaSq = sigma * sigma;
        double sigmaCubed = sigmaSq * sigma;
        double bandwidth = segment.kdeBandwidth;
        double[] dNumeratorByWeight = new double[CONTEXT_DIMENSIONS];
        double[] dDenominatorByWeight = new double[CONTEXT_DIMENSIONS];
        double numerator = 0.0;
        double denominator = 0.0;
        double dNumeratorByBandwidth = 0.0;
        double dDenominatorByBandwidth = 0.0;
        double dNumeratorBySigma = 0.0;
        double dDenominatorBySigma = 0.0;
        double clampedObservedStart = clamp(observedGfMin, MIN_GF, MAX_GF);
        double clampedObservedEnd = clamp(observedGfMax, MIN_GF, MAX_GF);

        for (int candidateIndex = 0; candidateIndex < selection.candidatePool.length; candidateIndex++) {
            DataPoint candidate = selection.candidatePool[candidateIndex];
            double weightedDistance = weightedSquaredDistance(
                    candidate.contextPoint,
                    queryPoint,
                    segment.contextDistanceWeights);
            double contextWeight = contextWeight(weightedDistance, sigma);
            if (!(contextWeight > 0.0)) {
                continue;
            }

            double kernelValue;
            double kernelBandwidthDerivative;
            double normalizationValue;
            double normalizationBandwidthDerivative;
            if (segment.intervalSamples) {
                kernelValue = intervalKernelIntegral(
                        clampedObservedStart,
                        clampedObservedEnd,
                        candidate.gfMin,
                        candidate.gfMax,
                        bandwidth);
                kernelBandwidthDerivative = intervalKernelIntegralBandwidthDerivative(
                        clampedObservedStart,
                        clampedObservedEnd,
                        candidate.gfMin,
                        candidate.gfMax,
                        bandwidth);
                normalizationValue = intervalKernelIntegral(MIN_GF, MAX_GF, candidate.gfMin, candidate.gfMax, bandwidth);
                normalizationBandwidthDerivative = intervalKernelIntegralBandwidthDerivative(
                        MIN_GF,
                        MAX_GF,
                        candidate.gfMin,
                        candidate.gfMax,
                        bandwidth);
            } else {
                double center = candidate.gfMin;
                double observedGf = observedGfMin;
                kernelValue = MathUtils.gaussian(observedGf, center, bandwidth);
                kernelBandwidthDerivative = gaussianBandwidthDerivative(observedGf, center, bandwidth);
                normalizationValue = MathUtils.gaussianIntegral(MIN_GF, MAX_GF, center, bandwidth);
                normalizationBandwidthDerivative =
                        gaussianIntegralBandwidthDerivative(MIN_GF, MAX_GF, center, bandwidth);
            }
            numerator += contextWeight * kernelValue;
            denominator += contextWeight * normalizationValue;
            dNumeratorByBandwidth += contextWeight * kernelBandwidthDerivative;
            dDenominatorByBandwidth += contextWeight * normalizationBandwidthDerivative;

            double sigmaDerivativeFactor = contextWeight * weightedDistance / sigmaCubed;
            dNumeratorBySigma += sigmaDerivativeFactor * kernelValue;
            dDenominatorBySigma += sigmaDerivativeFactor * normalizationValue;

            for (int dimension = 0; dimension < CONTEXT_DIMENSIONS; dimension++) {
                double delta = candidate.contextPoint[dimension] - queryPoint[dimension];
                double dcDw = -0.5 * contextWeight * delta * delta / sigmaSq;
                dNumeratorByWeight[dimension] += dcDw * kernelValue;
                dDenominatorByWeight[dimension] += dcDw * normalizationValue;
            }
        }

        if (!(numerator > MIN_PROBABILITY_MASS) || !(denominator > MIN_PROBABILITY_MASS)) {
            return null;
        }

        double[] weightGradients = new double[CONTEXT_DIMENSIONS];
        for (int dimension = 0; dimension < CONTEXT_DIMENSIONS; dimension++) {
            weightGradients[dimension] =
                    -(dNumeratorByWeight[dimension] / numerator) + (dDenominatorByWeight[dimension] / denominator);
        }
        double bandwidthGradient = -(dNumeratorByBandwidth / numerator) + (dDenominatorByBandwidth / denominator);
        double contextSigmaGradient = -(dNumeratorBySigma / numerator) + (dDenominatorBySigma / denominator);
        return new GradientStep(weightGradients, bandwidthGradient, contextSigmaGradient);
    }

    private static void applyGradient(SegmentLog segment, GradientStep gradient) {
        double[] updatedWeights = segment.contextDistanceWeights.clone();
        for (int i = 0; i < updatedWeights.length; i++) {
            double gradientStep = clip(
                    gradient.distanceWeightGradients[i],
                    BotConfig.Learning.WEIGHT_GRADIENT_CLIP);
            updatedWeights[i] -= BotConfig.Learning.WEIGHT_LEARNING_RATE * gradientStep;
            updatedWeights[i] += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                    * (segment.defaultModel.contextDistanceWeights[i] - updatedWeights[i]);
            if (updatedWeights[i] < WEIGHT_ZERO_EPSILON) {
                updatedWeights[i] = 0.0;
            }
        }
        if (!normalizeWeightMass(updatedWeights, segment.defaultWeightMass)) {
            System.arraycopy(
                    segment.defaultModel.contextDistanceWeights,
                    0,
                    updatedWeights,
                    0,
                    CONTEXT_DIMENSIONS);
        }

        double logBandwidth = Math.log(segment.kdeBandwidth);
        double bandwidthLogGradient = segment.kdeBandwidth * gradient.bandwidthGradient;
        logBandwidth -= BotConfig.Learning.LOG_PARAMETER_LEARNING_RATE
                * clip(bandwidthLogGradient, BotConfig.Learning.LOG_PARAMETER_GRADIENT_CLIP);
        logBandwidth += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                * (Math.log(segment.defaultModel.kdeBandwidth) - logBandwidth);
        double updatedBandwidth = clamp(
                Math.exp(logBandwidth),
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);

        double logContextSigma = Math.log(segment.contextWeightSigma);
        double contextSigmaLogGradient = segment.contextWeightSigma * gradient.contextSigmaGradient;
        logContextSigma -= BotConfig.Learning.LOG_PARAMETER_LEARNING_RATE
                * clip(contextSigmaLogGradient, BotConfig.Learning.LOG_PARAMETER_GRADIENT_CLIP);
        logContextSigma += BotConfig.Learning.MODEL_DEFAULT_PULL_RATE
                * (Math.log(segment.defaultModel.contextWeightSigma) - logContextSigma);
        double updatedContextSigma =
                clamp(
                        Math.exp(logContextSigma),
                        BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                        BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);

        segment.applyModel(updatedWeights, updatedBandwidth, updatedContextSigma);
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
                                                              WaveContextFeatures.WaveContext context) {
        NeighborSelection selection = selectNeighbors(
                segment,
                createUncheckedContextPoint(context),
                MODEL_CANDIDATE_POOL);
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
        return shouldMirrorDistribution(context.lateralDirectionSign)
                ? new MirroredGuessFactorDistribution(distribution)
                : distribution;
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

    private static double gaussianBandwidthDerivative(double x, double mean, double bandwidth) {
        double density = MathUtils.gaussian(x, mean, bandwidth);
        double delta = x - mean;
        return density * ((delta * delta) / (bandwidth * bandwidth * bandwidth) - 1.0 / bandwidth);
    }

    private static double gaussianIntegralBandwidthDerivative(double lowerBound,
                                                              double upperBound,
                                                              double mean,
                                                              double bandwidth) {
        if (lowerBound >= upperBound) {
            return 0.0;
        }
        double lowerDensity = MathUtils.gaussian(lowerBound, mean, bandwidth);
        double upperDensity = MathUtils.gaussian(upperBound, mean, bandwidth);
        return ((lowerBound - mean) * lowerDensity - (upperBound - mean) * upperDensity) / bandwidth;
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

    private static double intervalKernelIntegralBandwidthDerivative(double gfStart,
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
            return gaussianIntegralBandwidthDerivative(clampedStart, clampedEnd, lowerBound, bandwidth);
        }
        double endDerivative = -standardNormalPdf((clampedEnd - upperBound) / bandwidth)
                + standardNormalPdf((clampedEnd - lowerBound) / bandwidth);
        double startDerivative = -standardNormalPdf((clampedStart - upperBound) / bandwidth)
                + standardNormalPdf((clampedStart - lowerBound) / bandwidth);
        return (endDerivative - startDerivative) / intervalWidth;
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

    private static double[] createContextPoint(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Wave-log context must be non-null");
        }
        if (!Double.isFinite(context.lateralVelocity)) {
            throw new IllegalArgumentException("Wave-log point requires finite lateral velocity");
        }
        if (!Double.isFinite(context.advancingVelocity)) {
            throw new IllegalArgumentException("Wave-log point requires finite advancing velocity");
        }
        if (context.flightTicks <= 0) {
            throw new IllegalArgumentException("Wave-log point requires positive flight ticks");
        }
        if (context.ticksSinceVelocityReversal < 0) {
            throw new IllegalArgumentException("Wave-log point requires non-negative reversal ticks");
        }
        if (context.ticksSinceDecel < 0) {
            throw new IllegalArgumentException("Wave-log point requires non-negative decel ticks");
        }
        if (!Double.isFinite(context.wallAhead) || !Double.isFinite(context.wallReverse)) {
            throw new IllegalArgumentException("Wave-log point requires finite wall features");
        }
        if (!Double.isFinite(context.currentGF)) {
            throw new IllegalArgumentException("Wave-log point requires finite current GF");
        }
        if (WaveContextFeatures.normalizeDirectionSign(context.lateralDirectionSign) == 0) {
            throw new IllegalArgumentException("Wave-log point requires a non-zero lateral direction sign");
        }
        return createUncheckedContextPoint(context);
    }

    private static double[] createUncheckedContextPoint(WaveContextFeatures.WaveContext context) {
        double[] contextPoint = new double[CONTEXT_DIMENSIONS];
        contextPoint[0] = normalizeAbsoluteVelocity(context.lateralVelocity);
        contextPoint[1] = normalizeSignedVelocity(context.advancingVelocity);
        contextPoint[2] = normalizeFlightTicks(context.flightTicks);
        contextPoint[3] = normalizeAccelerationSign(context.accelerationSign);
        contextPoint[4] = normalizeReversalTicks(context.ticksSinceVelocityReversal);
        contextPoint[5] = normalizeDecelTicks(context.ticksSinceDecel);
        contextPoint[6] = clamp(context.wallAhead, 0.0, 1.0);
        contextPoint[7] = clamp(context.wallReverse, 0.0, 1.0);
        contextPoint[8] = normalizeGuessFactor(canonicalizeGuessFactor(context.currentGF, context.lateralDirectionSign));
        return contextPoint;
    }

    private static double normalizeAbsoluteVelocity(double velocity) {
        return clamp(Math.abs(velocity) / MAX_LATERAL_VELOCITY, 0.0, 1.0);
    }

    private static double normalizeSignedVelocity(double velocity) {
        return clamp((velocity + MAX_LATERAL_VELOCITY) / (2.0 * MAX_LATERAL_VELOCITY), 0.0, 1.0);
    }

    private static double normalizeFlightTicks(int flightTicks) {
        return clamp(flightTicks / FLIGHT_TICKS_SCALE, 0.0, 1.0);
    }

    private static double normalizeAccelerationSign(int accelerationSign) {
        return clamp((accelerationSign + 1.0) * 0.5, 0.0, 1.0);
    }

    private static double normalizeReversalTicks(int ticksSinceVelocityReversal) {
        return clamp(ticksSinceVelocityReversal / REVERSAL_TICKS_SCALE, 0.0, 1.0);
    }

    private static double normalizeDecelTicks(int ticksSinceDecel) {
        return clamp(ticksSinceDecel / DECEL_TICKS_SCALE, 0.0, 1.0);
    }

    private static double normalizeGuessFactor(double guessFactor) {
        return clamp((guessFactor + 1.0) * 0.5, 0.0, 1.0);
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static void startBattle() {
        gunSegment.resetBattleState();
        movementSegment.resetBattleState();
    }

    public static String getTargetingModelName() {
        return describeModel(gunSegment, DEFAULT_TARGETING_MODEL);
    }

    public static String getTargetingModelSummary() {
        return describeModelSummary(gunSegment, DEFAULT_TARGETING_MODEL);
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

    public static String getDefaultMovementModelSummary() {
        return describeModel(DEFAULT_MOVEMENT_MODEL);
    }

    private static boolean isDefaultModel(SegmentLog segment, ModelSpec defaultModel) {
        if (!nearlyEqual(segment.kdeBandwidth, defaultModel.kdeBandwidth)
                || !nearlyEqual(segment.contextWeightSigma, defaultModel.contextWeightSigma)) {
            return false;
        }
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            if (!nearlyEqual(segment.contextDistanceWeights[i], defaultModel.contextDistanceWeights[i])) {
                return false;
            }
        }
        return true;
    }

    private static void writeModel(DataOutputStream out, SegmentLog segment) throws IOException {
        double weightMass = segment.defaultWeightMass;
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            out.writeShort(encodeUnsigned16(segment.contextDistanceWeights[i], 0.0, weightMass));
        }
        out.writeShort(encodeUnsigned16(
                segment.kdeBandwidth,
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH));
        out.writeShort(encodeUnsigned16(
                segment.contextWeightSigma,
                BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA));
    }

    private static void loadPersistedPayload(int sectionVersion, byte[] payload) {
        if (sectionVersion == LEGACY_2_MODEL_SECTION_VERSION) {
            loadLegacy2ModelPayload(payload);
            return;
        }
        if (sectionVersion == LEGACY_4_MODEL_SECTION_VERSION) {
            loadLegacy4ModelPayload(payload);
            return;
        }
        if (sectionVersion != PERSISTENCE_SECTION_VERSION) {
            throw new IllegalStateException("Unsupported WaveLog section version " + sectionVersion);
        }
        if (payload.length != MODEL_SECTION_BYTES) {
            throw new IllegalStateException("Unexpected WaveLog model payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            gunSegment.setModel(readModel(in, DEFAULT_TARGETING_MODEL));
            movementSegment.setModel(readModel(in, DEFAULT_MOVEMENT_MODEL));
            if (in.available() != 0) {
                throw new IllegalStateException("WaveLog model payload contained trailing bytes");
            }
            persistedModelLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable WaveLog model payload", e);
        }
    }

    private static void loadLegacy4ModelPayload(byte[] payload) {
        // Legacy save format with 4 models - we only load the first 2 (gun and movement)
        if (payload.length != LEGACY_MODEL_SECTION_BYTES * 2) {
            throw new IllegalStateException("Unexpected legacy 4-model WaveLog payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            gunSegment.setModel(readModel(in, DEFAULT_TARGETING_MODEL));
            movementSegment.setModel(readModel(in, DEFAULT_MOVEMENT_MODEL));
            // Skip the remaining antiSurfer models (no longer used)
            persistedModelLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable legacy 4-model WaveLog payload", e);
        }
    }

    private static void loadLegacy2ModelPayload(byte[] payload) {
        if (payload.length != LEGACY_MODEL_SECTION_BYTES) {
            throw new IllegalStateException("Unexpected legacy WaveLog model payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            gunSegment.setModel(readModel(in, DEFAULT_TARGETING_MODEL));
            movementSegment.setModel(readModel(in, DEFAULT_MOVEMENT_MODEL));
            if (in.available() != 0) {
                throw new IllegalStateException("Legacy WaveLog model payload contained trailing bytes");
            }
            persistedModelLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable legacy WaveLog model payload", e);
        }
    }

    private static ModelSpec readModel(DataInputStream in, ModelSpec defaultModel) throws IOException {
        double targetWeightMass = sumPositiveWeights(defaultModel.contextDistanceWeights);
        double[] weights = new double[CONTEXT_DIMENSIONS];
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            weights[i] = decodeUnsigned16(in.readUnsignedShort(), 0.0, targetWeightMass);
        }
        if (!(sumPositiveWeights(weights) > MODEL_EPSILON)) {
            throw new IllegalStateException("WaveLog weights must contain positive mass");
        }
        double bandwidth = decodeUnsigned16(
                in.readUnsignedShort(),
                BotConfig.Learning.MIN_KDE_BANDWIDTH,
                BotConfig.Learning.MAX_KDE_BANDWIDTH);
        double contextSigma = decodeUnsigned16(
                in.readUnsignedShort(),
                BotConfig.Learning.MIN_CONTEXT_WEIGHT_SIGMA,
                BotConfig.Learning.MAX_CONTEXT_WEIGHT_SIGMA);
        return new ModelSpec(defaultModel.name, weights, bandwidth, contextSigma);
    }

    private static int encodeUnsigned16(double value, double min, double max) {
        if (!(max > min)) {
            throw new IllegalArgumentException("WaveLog encode range must be positive");
        }
        double normalized = clamp((value - min) / (max - min), 0.0, 1.0);
        return (int) Math.round(normalized * PERSISTED_UINT16_MAX);
    }

    private static double decodeUnsigned16(int encodedValue, double min, double max) {
        if (!(max > min)) {
            throw new IllegalArgumentException("WaveLog decode range must be positive");
        }
        double normalized = clamp(encodedValue / PERSISTED_UINT16_MAX, 0.0, 1.0);
        return min + normalized * (max - min);
    }

    private static String describeModelSummary(SegmentLog segment, ModelSpec defaultModel) {
        return isDefaultModel(segment, defaultModel) ? "Default" : describeModel(segment, defaultModel);
    }

    private static String describeModel(ModelSpec model) {
        return describeModel(
                model.contextDistanceWeights,
                model.kdeBandwidth,
                model.contextWeightSigma,
                true);
    }

    private static String describeModel(SegmentLog segment, ModelSpec defaultModel) {
        return describeModel(
                segment.contextDistanceWeights,
                segment.kdeBandwidth,
                segment.contextWeightSigma,
                isDefaultModel(segment, defaultModel));
    }

    private static String describeModel(double[] weights,
                                        double kdeBandwidth,
                                        double contextWeightSigma,
                                        boolean appendDefaultMarker) {
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
                    .append(String.format(Locale.US, "%.2f", weight));
        }
        if (builder.length() == 0) {
            builder.append("allOff");
        }
        builder.append(String.format(
                Locale.US,
                " bw=%.2f s=%.2f k=%d",
                kdeBandwidth,
                contextWeightSigma,
                MODEL_CANDIDATE_POOL));
        if (appendDefaultMarker) {
            builder.append(" (default)");
        }
        return builder.toString();
    }

    private static String contextLabel(int index) {
        switch (index) {
        case 0:
            return "lat";
        case 1:
            return "adv";
        case 2:
            return "flight";
        case 3:
            return "accel";
        case 4:
            return "reversal";
        case 5:
            return "decel";
        case 6:
            return "wallAhead";
        case 7:
            return "wallReverse";
        case 8:
            return "currentGf";
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
            if (weights[i] > WEIGHT_ZERO_EPSILON) {
                weights[i] *= scale;
            } else {
                weights[i] = 0.0;
            }
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

    private static double clip(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }
}
