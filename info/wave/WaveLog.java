package oog.mega.saguaro.info.wave;

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
    static final int CONTEXT_DIMENSIONS = 21;
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
    private static final int MODEL_CANDIDATE_POOL = 7;
    private static final double MAX_LATERAL_VELOCITY = 8.0;
    private static final double MIN_VELOCITY_DELTA = -2.0;
    private static final double MAX_VELOCITY_DELTA = 1.0;
    private static final double FLIGHT_TICKS_SCALE = Wave.nominalFlightTicks(
            Math.hypot(
                    800.0 - WaveContextFeatures.ROBOT_DIAMETER,
                    600.0 - WaveContextFeatures.ROBOT_DIAMETER),
            Wave.bulletSpeed(3.0));
    private static final double RELATIVE_TICKS_CAP = 70.0;
    private static final double DISTANCE_LAST_10_SCALE = 10.0 * MAX_LATERAL_VELOCITY;
    private static final double DISTANCE_LAST_20_SCALE = 20.0 * MAX_LATERAL_VELOCITY;
    private static final double BATTLE_TIME_SCALE = 500.0;
    private static final double FEATURE_TRANSFORM_EPSILON = 1e-4;
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final double MIN_INTERVAL_WIDTH = 1e-9;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);
    private static final double WEIGHT_ZERO_EPSILON = 1e-6;
    private static final double MODEL_EPSILON = 1e-9;
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
            0.75,
            0.55
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
            0.60,
            0.85
    };
    private static final double[] DEFAULT_TARGETING_FEATURE_BIASES = new double[]{
            0.02, 0.07, 0.02, 0.36, 0.00, 0.05, 0.05, 0.03, 0.03, 0.02, 0.02,
            0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.00, 0.02
    };
    private static final double[] DEFAULT_TARGETING_FEATURE_EXPONENTS = new double[]{
            0.38, 0.55, 0.80, 0.50, 1.00, 0.65, 0.65, 0.75, 0.75, 0.70, 0.70,
            0.60, 0.35, 0.35, 0.45, 0.45, 0.45, 0.45, 0.85, 1.00, 0.50
    };
    private static final double[] DEFAULT_MOVEMENT_FEATURE_BIASES = new double[]{
            0.02, 0.05, 0.02, 0.18, 0.00, 0.05, 0.05, 0.03, 0.03, 0.02, 0.02,
            0.02, 0.01, 0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.00, 0.02
    };
    private static final double[] DEFAULT_MOVEMENT_FEATURE_EXPONENTS = new double[]{
            0.45, 0.55, 0.80, 0.55, 1.00, 0.65, 0.60, 0.80, 0.75, 0.70, 0.70,
            0.55, 0.40, 0.40, 0.50, 0.50, 0.50, 0.50, 0.85, 1.00, 0.55
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
        logResult(gunSegment, context, gfMin, gfMax, saveObservation);
    }

    public static void logGunInterval(WaveContextFeatures.WaveContext context,
                                      double gfMin,
                                      double gfMax,
                                      boolean saveObservation,
                                      boolean updateModel) {
        logGunInterval(context, gfMin, gfMax, saveObservation);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context, double gf) {
        logMovementResult(context, gf, true);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation) {
        logResult(movementSegment, context, gf, gf, saveObservation);
    }

    public static void logMovementResult(WaveContextFeatures.WaveContext context,
                                         double gf,
                                         boolean saveObservation,
                                         boolean updateModel) {
        logMovementResult(context, gf, saveObservation);
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
                                  boolean saveObservation) {
        if (!Double.isFinite(gfMin) || !Double.isFinite(gfMax) || gfMin > gfMax) {
            throw new IllegalArgumentException("Wave-log result requires ordered finite GF bounds");
        }
        double[] contextPoint = createContextPoint(segment, context);
        double[] canonicalGfRange = canonicalizeGuessFactorRange(gfMin, gfMax, context.momentumDirectionSign);
        double canonicalGfMin = canonicalGfRange[0];
        double canonicalGfMax = canonicalGfRange[1];
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
        int candidateCount = candidateCountForLogSize(segment.log.size());
        NeighborSelection selection = selectNeighbors(
                segment,
                createUncheckedContextPoint(segment, context),
                candidateCount);
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
        return Math.min(MODEL_CANDIDATE_POOL, Math.max(1, logSize / 5));
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

    private static double[] createContextPoint(SegmentLog segment,
                                               WaveContextFeatures.WaveContext context) {
        if (segment == null) {
            throw new IllegalArgumentException("Wave-log segment must be non-null");
        }
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
        if (WaveContextFeatures.normalizeDirectionSign(context.momentumDirectionSign) == 0) {
            throw new IllegalArgumentException("Wave-log point requires a non-zero momentum direction sign");
        }
        return createUncheckedContextPoint(segment, context);
    }

    private static double[] createUncheckedContextPoint(SegmentLog segment,
                                                        WaveContextFeatures.WaveContext context) {
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
        normalized[FEATURE_MAE_WALL_AHEAD] = clamp(context.wallAhead, 0.0, 1.0);
        normalized[FEATURE_MAE_WALL_REVERSE] = clamp(context.wallReverse, 0.0, 1.0);
        normalized[FEATURE_STICK_WALL_AHEAD] = clamp(context.stickWallAhead, 0.0, 1.0);
        normalized[FEATURE_STICK_WALL_REVERSE] = clamp(context.stickWallReverse, 0.0, 1.0);
        normalized[FEATURE_STICK_WALL_AHEAD2] = clamp(context.stickWallAhead2, 0.0, 1.0);
        normalized[FEATURE_STICK_WALL_REVERSE2] = clamp(context.stickWallReverse2, 0.0, 1.0);
        normalized[FEATURE_CURRENT_GF] = normalizeGuessFactor(
                canonicalizeGuessFactor(context.currentGF, context.momentumDirectionSign));
        normalized[FEATURE_DID_HIT] = normalizeBoolean(context.didHit);
        normalized[FEATURE_BATTLE_TIME] = normalizeBattleTime(context.battleTime);

        double[] transformed = new double[CONTEXT_DIMENSIONS];
        for (int i = 0; i < CONTEXT_DIMENSIONS; i++) {
            transformed[i] = transformFeature(
                    normalized[i],
                    segment.defaultModel.featureBiases[i],
                    segment.defaultModel.featureExponents[i]);
        }
        return transformed;
    }

    private static double normalizePower(double bulletSpeed) {
        return clamp(((20.0 - bulletSpeed) / 3.0) / 3.0, 0.0, 1.0);
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

    private static double normalizeVelocityDelta(double velocityDelta) {
        return clamp(
                (velocityDelta - MIN_VELOCITY_DELTA) / (MAX_VELOCITY_DELTA - MIN_VELOCITY_DELTA),
                0.0,
                1.0);
    }

    private static double normalizeRelativeTicks(int ticks, int flightTicks) {
        return clamp(Math.min(RELATIVE_TICKS_CAP, ticks) / Math.max(1.0, flightTicks), 0.0, 1.0);
    }

    private static double normalizeDistance(double distance, double scale) {
        return clamp(distance / scale, 0.0, 1.0);
    }

    private static double normalizeRelativeHeading(double relativeHeading) {
        return clamp(Math.abs(relativeHeading) / Math.PI, 0.0, 1.0);
    }

    private static double normalizeBoolean(boolean value) {
        return value ? 1.0 : 0.0;
    }

    private static double normalizeBattleTime(long battleTime) {
        return clamp(Math.min(BATTLE_TIME_SCALE, battleTime) / BATTLE_TIME_SCALE, 0.0, 1.0);
    }

    private static double transformFeature(double value, double bias, double exponent) {
        return Math.pow(FEATURE_TRANSFORM_EPSILON + bias + clamp(value, 0.0, 1.0), exponent);
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
                isDefaultModel(segment, defaultModel),
                candidateCountForLogSize(segment.log.size()));
    }

    private static String describeModel(double[] weights,
                                        double kdeBandwidth,
                                        double contextWeightSigma,
                                        boolean appendDefaultMarker) {
        return describeModel(weights, kdeBandwidth, contextWeightSigma, appendDefaultMarker, MODEL_CANDIDATE_POOL);
    }

    private static String describeModel(double[] weights,
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
}
