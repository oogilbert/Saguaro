package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.PhysicsUtil;

final class CostanzaExpert {
    private static final double DISTINCT_CENTER_EPSILON = 1e-9;

    private CostanzaExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context,
                                                         EnemyInfo enemy,
                                                         Info info) {
        ExpertPrediction prediction = createGunPrediction(context, enemy, info);
        return prediction != null ? prediction.distribution : null;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context,
                                                              Info info) {
        ExpertPrediction prediction = createMovementPrediction(context, info);
        return prediction != null ? prediction.distribution : null;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context,
                                                EnemyInfo enemy,
                                                Info info) {
        return createGunPrediction(
                context,
                AntiSurferSourceExpertCatalog.createGunSourceSnapshot(context, enemy, info));
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context,
                                                AntiSurferExpertSnapshot sourceSnapshot) {
        return createPrediction(
                context,
                sourceSnapshot,
                BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH);
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     Info info) {
        return createMovementPrediction(
                context,
                AntiSurferSourceExpertCatalog.createMovementSourceSnapshot(context, info));
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     AntiSurferExpertSnapshot sourceSnapshot) {
        return createPrediction(
                context,
                sourceSnapshot,
                BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH);
    }

    private static ExpertPrediction createPrediction(WaveContextFeatures.WaveContext context,
                                                     AntiSurferExpertSnapshot sourceSnapshot,
                                                     double bandwidth) {
        List<ExpertPrediction> sourcePredictions =
                sourceSnapshot != null ? sourceSnapshot.activePredictions() : Collections.<ExpertPrediction>emptyList();
        if (sourcePredictions == null || sourcePredictions.isEmpty()) {
            return null;
        }
        double[] preciseRange = AntiSurferPreciseMea.range(context);
        List<PredictionPoint> points = buildSortedPoints(sourcePredictions, preciseRange[0], preciseRange[1]);
        double selectedGf = selectCostanzaGf(points, preciseRange[0], preciseRange[1]);
        double clampedGf = AntiSurferPreciseMea.clampGf(context, selectedGf);
        GuessFactorDistribution distribution = new KDEDistribution(new double[]{clampedGf}, bandwidth);
        PhysicsUtil.Trajectory trajectory = selectDisplayTrajectory(points, clampedGf);
        return new ExpertPrediction(distribution, trajectory, clampedGf);
    }

    private static List<PredictionPoint> buildSortedPoints(List<ExpertPrediction> sourcePredictions,
                                                           double minGf,
                                                           double maxGf) {
        List<PredictionPoint> points = new ArrayList<PredictionPoint>(sourcePredictions.size());
        for (ExpertPrediction prediction : sourcePredictions) {
            double clampedCenter = Math.max(minGf, Math.min(maxGf, prediction.centerGf));
            points.add(new PredictionPoint(clampedCenter, prediction));
        }
        Collections.sort(points, Comparator.comparingDouble(point -> point.centerGf));
        return points;
    }

    private static double selectCostanzaGf(List<PredictionPoint> points,
                                           double minGf,
                                           double maxGf) {
        if (points.isEmpty()) {
            return 0.5 * (minGf + maxGf);
        }
        List<Double> candidates = new ArrayList<Double>();
        candidates.add(minGf);
        candidates.add(maxGf);
        double previousCenter = Double.NaN;
        for (PredictionPoint point : points) {
            if (!Double.isFinite(previousCenter)
                    || Math.abs(point.centerGf - previousCenter) > DISTINCT_CENTER_EPSILON) {
                candidates.add(point.centerGf);
                previousCenter = point.centerGf;
            }
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            double midpoint = 0.5 * (points.get(i).centerGf + points.get(i + 1).centerGf);
            candidates.add(Math.max(minGf, Math.min(maxGf, midpoint)));
        }

        double bestCandidate = candidates.get(0);
        double bestMinDistance = Double.NEGATIVE_INFINITY;
        double bestAverageDistance = Double.NEGATIVE_INFINITY;
        double bestAbsoluteGf = Double.NEGATIVE_INFINITY;
        for (double candidate : candidates) {
            double minDistance = Double.POSITIVE_INFINITY;
            double averageDistance = 0.0;
            for (PredictionPoint point : points) {
                double distance = Math.abs(candidate - point.centerGf);
                minDistance = Math.min(minDistance, distance);
                averageDistance += distance;
            }
            averageDistance /= points.size();
            double absoluteGf = Math.abs(candidate);
            if (isBetterCandidate(
                    minDistance,
                    averageDistance,
                    absoluteGf,
                    bestMinDistance,
                    bestAverageDistance,
                    bestAbsoluteGf)) {
                bestCandidate = candidate;
                bestMinDistance = minDistance;
                bestAverageDistance = averageDistance;
                bestAbsoluteGf = absoluteGf;
            }
        }
        return bestCandidate;
    }

    private static boolean isBetterCandidate(double minDistance,
                                             double averageDistance,
                                             double absoluteGf,
                                             double bestMinDistance,
                                             double bestAverageDistance,
                                             double bestAbsoluteGf) {
        if (minDistance > bestMinDistance + DISTINCT_CENTER_EPSILON) {
            return true;
        }
        if (minDistance < bestMinDistance - DISTINCT_CENTER_EPSILON) {
            return false;
        }
        if (averageDistance > bestAverageDistance + DISTINCT_CENTER_EPSILON) {
            return true;
        }
        if (averageDistance < bestAverageDistance - DISTINCT_CENTER_EPSILON) {
            return false;
        }
        return absoluteGf > bestAbsoluteGf + DISTINCT_CENTER_EPSILON;
    }

    private static PhysicsUtil.Trajectory selectDisplayTrajectory(List<PredictionPoint> points,
                                                                  double selectedGf) {
        if (points.isEmpty()) {
            return null;
        }
        PredictionPoint left = null;
        PredictionPoint right = null;
        for (PredictionPoint point : points) {
            if (point.centerGf <= selectedGf) {
                left = point;
            }
            if (point.centerGf >= selectedGf) {
                right = point;
                break;
            }
        }
        if (left == null) {
            return right.prediction.trajectory;
        }
        if (right == null) {
            return left.prediction.trajectory;
        }
        if (left == right || Math.abs(right.centerGf - left.centerGf) <= DISTINCT_CENTER_EPSILON) {
            return left.prediction.trajectory;
        }
        double fraction = (selectedGf - left.centerGf) / (right.centerGf - left.centerGf);
        return blendTrajectories(left.prediction.trajectory, right.prediction.trajectory, fraction);
    }

    private static PhysicsUtil.Trajectory blendTrajectories(PhysicsUtil.Trajectory leftTrajectory,
                                                            PhysicsUtil.Trajectory rightTrajectory,
                                                            double rightWeight) {
        double normalizedRight = Math.max(0.0, Math.min(1.0, rightWeight));
        double normalizedLeft = 1.0 - normalizedRight;
        int maxLength = Math.max(leftTrajectory.length(), rightTrajectory.length());
        PhysicsUtil.PositionState[] states = new PhysicsUtil.PositionState[maxLength];
        for (int i = 0; i < maxLength; i++) {
            PhysicsUtil.PositionState leftState = leftTrajectory.stateAt(i);
            PhysicsUtil.PositionState rightState = rightTrajectory.stateAt(i);
            states[i] = new PhysicsUtil.PositionState(
                    normalizedLeft * leftState.x + normalizedRight * rightState.x,
                    normalizedLeft * leftState.y + normalizedRight * rightState.y,
                    oog.mega.saguaro.math.MathUtils.normalizeAngle(
                            leftState.heading
                                    + oog.mega.saguaro.math.MathUtils.normalizeAngle(rightState.heading - leftState.heading)
                                    * normalizedRight),
                    normalizedLeft * leftState.velocity + normalizedRight * rightState.velocity);
        }
        return new PhysicsUtil.Trajectory(states);
    }
    private static final class PredictionPoint {
        final double centerGf;
        final ExpertPrediction prediction;

        PredictionPoint(double centerGf, ExpertPrediction prediction) {
            this.centerGf = centerGf;
            this.prediction = prediction;
        }
    }
}
