package oog.mega.saguaro.info.state;

import ags.utils.dataStructures.MaxHeap;
import ags.utils.dataStructures.trees.thirdGenKD.KdTree;
import ags.utils.dataStructures.trees.thirdGenKD.WeightedSquareEuclideanDistanceFunction;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.RobotHitbox;

final class LocalHitRateKnnModel {
    private static final int FEATURE_DIMENSIONS = 3;
    private static final int MAX_NEIGHBORS = 50;
    private static final double FLIGHT_TICKS_SCALE = 70.0;
    private static final double ANGULAR_WIDTH_SCALE = 1.0;

    private final double[] distanceWeights = new double[]{1.0, 1.0, 1.0};
    private final WeightedSquareEuclideanDistanceFunction distanceFunction =
            new WeightedSquareEuclideanDistanceFunction(distanceWeights);
    private KdTree<OutcomePoint> tree = new KdTree<OutcomePoint>(FEATURE_DIMENSIONS);

    void clear() {
        tree = new KdTree<OutcomePoint>(FEATURE_DIMENSIONS);
    }

    void addObservation(double[] point, boolean hit) {
        if (!isUsablePoint(point)) {
            return;
        }
        tree.addPoint(point.clone(), new OutcomePoint(hit));
    }

    double estimate(double[] queryPoint,
                    double priorMean,
                    double priorStrength,
                    double minValue,
                    double maxValue) {
        if (!isUsablePoint(queryPoint) || tree.size() == 0) {
            return clamp(priorMean, minValue, maxValue);
        }
        int neighborCount = Math.min(MAX_NEIGHBORS, tree.size());
        MaxHeap<OutcomePoint> neighbors = tree.findNearestNeighbors(queryPoint, neighborCount, distanceFunction);
        double weightedHits = 0.0;
        double weightedShots = 0.0;
        while (neighbors.size() > 0) {
            double distanceSquared = neighbors.getMaxKey();
            OutcomePoint point = neighbors.getMax();
            double neighborWeight = 1.0 / (1.0 + distanceSquared);
            weightedShots += neighborWeight;
            if (point.hit) {
                weightedHits += neighborWeight;
            }
            neighbors.removeMax();
        }
        double posteriorMean = (weightedHits + priorMean * priorStrength) / (weightedShots + priorStrength);
        return clamp(posteriorMean, minValue, maxValue);
    }

    static double[] pointForWave(Wave wave) {
        return wave != null ? pointForContext(wave.fireTimeContext) : null;
    }

    static double[] pointForContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            return null;
        }
        double angularWidth = angularWidth(
                context.sourceX,
                context.sourceY,
                context.targetX,
                context.targetY);
        double[] preciseRange = MathUtils.inFieldMaxEscapeGfRange(
                context.sourceX,
                context.sourceY,
                context.targetX,
                context.targetY,
                context.bulletSpeed,
                context.battlefieldWidth,
                context.battlefieldHeight);
        double compression = preciseWidthFraction(preciseRange);
        return new double[]{
                clamp(context.flightTicks / FLIGHT_TICKS_SCALE, 0.0, 1.0),
                clamp(angularWidth / ANGULAR_WIDTH_SCALE, 0.0, 1.0),
                compression
        };
    }

    static double[] pointForGeometry(double sourceX,
                                     double sourceY,
                                     double targetX,
                                     double targetY,
                                     double bulletSpeed,
                                     double battlefieldWidth,
                                     double battlefieldHeight) {
        if (!Double.isFinite(sourceX)
                || !Double.isFinite(sourceY)
                || !Double.isFinite(targetX)
                || !Double.isFinite(targetY)
                || !Double.isFinite(bulletSpeed)
                || !(bulletSpeed > 0.0)
                || !Double.isFinite(battlefieldWidth)
                || !Double.isFinite(battlefieldHeight)) {
            return null;
        }
        double distance = Math.hypot(targetX - sourceX, targetY - sourceY);
        int flightTicks = Wave.nominalFlightTicks(distance, bulletSpeed);
        double angularWidth = angularWidth(sourceX, sourceY, targetX, targetY);
        double[] preciseRange = MathUtils.inFieldMaxEscapeGfRange(
                sourceX,
                sourceY,
                targetX,
                targetY,
                bulletSpeed,
                battlefieldWidth,
                battlefieldHeight);
        double compression = preciseWidthFraction(preciseRange);
        return new double[]{
                clamp(flightTicks / FLIGHT_TICKS_SCALE, 0.0, 1.0),
                clamp(angularWidth / ANGULAR_WIDTH_SCALE, 0.0, 1.0),
                compression
        };
    }

    static int flightBucket(double flightTicks, double[] upperBounds) {
        double clampedTicks = Math.max(1.0, flightTicks);
        for (int i = 0; i < upperBounds.length; i++) {
            if (clampedTicks <= upperBounds[i]) {
                return i;
            }
        }
        return upperBounds.length - 1;
    }

    private static double angularWidth(double sourceX, double sourceY, double targetX, double targetY) {
        double[] angularInterval = RobotHitbox.angularInterval(sourceX, sourceY, targetX, targetY);
        if (angularInterval == null) {
            return 0.0;
        }
        return clamp(angularInterval[1] - angularInterval[0], 0.0, Math.PI * 2.0);
    }

    private static boolean isUsablePoint(double[] point) {
        if (point == null || point.length != FEATURE_DIMENSIONS) {
            return false;
        }
        for (double value : point) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static double preciseWidthFraction(double[] preciseRange) {
        if (preciseRange == null || preciseRange.length < 2) {
            return 1.0;
        }
        return clamp(0.5 * Math.max(0.0, preciseRange[1] - preciseRange[0]), 0.0, 1.0);
    }

    private static double clamp(double value, double minValue, double maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static final class OutcomePoint {
        final boolean hit;

        OutcomePoint(boolean hit) {
            this.hit = hit;
        }
    }
}
