package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.RobotHitbox;

final class GuessFactorDangerAnalyzer {
    private static final double GF_DUPLICATE_EPS = 1e-3;

    private static final class CandidateDanger {
        final double gf;
        final double danger;

        CandidateDanger(double gf, double danger) {
            this.gf = gf;
            this.danger = danger;
        }
    }

    private static final class DangerCurveContext {
        final double minGf;
        final double maxGf;
        final double referenceBearing;
        final double mea;
        final double hitboxDistance;
        final List<double[]> shadowGfIntervals;
        final GuessFactorDistribution distribution;

        DangerCurveContext(double minGf,
                           double maxGf,
                           double referenceBearing,
                           double mea,
                           double hitboxDistance,
                           List<double[]> shadowGfIntervals,
                           GuessFactorDistribution distribution) {
            this.minGf = minGf;
            this.maxGf = maxGf;
            this.referenceBearing = referenceBearing;
            this.mea = mea;
            this.hitboxDistance = hitboxDistance;
            this.shadowGfIntervals = shadowGfIntervals;
            this.distribution = distribution;
        }
    }

    double[] findKSafestGFs(Wave wave,
                            int k,
                            double minGF,
                            double maxGF,
                            double hitboxDistance,
                            double refX,
                            double refY,
                            List<BulletShadowUtil.ShadowInterval> waveShadows,
                            GuessFactorDistribution distribution) {
        double mea = MathUtils.maxEscapeAngle(wave.speed);
        double referenceBearing = Math.atan2(refX - wave.originX, refY - wave.originY);
        List<double[]> activeShadowGfIntervals;
        if (waveShadows == null || waveShadows.isEmpty()) {
            activeShadowGfIntervals = java.util.Collections.emptyList();
        } else {
            List<BulletShadowUtil.WeightedGfInterval> weightedShadowIntervals =
                    BulletShadowUtil.mergeAndClipWeightedIntervals(
                            BulletShadowUtil.allGfIntervals(waveShadows, referenceBearing, mea), -1.0, 1.0);
            activeShadowGfIntervals = new ArrayList<>(weightedShadowIntervals.size());
            for (BulletShadowUtil.WeightedGfInterval interval : weightedShadowIntervals) {
                activeShadowGfIntervals.add(new double[]{interval.startGf, interval.endGf});
            }
            activeShadowGfIntervals = BulletShadowUtil.mergeAndClipIntervals(activeShadowGfIntervals, -1.0, 1.0);
        }

        double clampedMin = Math.max(-1.0, minGF);
        double clampedMax = Math.min(1.0, maxGF);
        if (clampedMin > clampedMax) {
            throw new IllegalStateException(
                    "Invalid reachable GF interval: minGF=" + minGF + ", maxGF=" + maxGF);
        }
        if (Math.abs(clampedMax - clampedMin) < 1e-9) {
            return new double[]{clampedMin};
        }

        DangerCurveContext dangerContext = new DangerCurveContext(
                clampedMin,
                clampedMax,
                referenceBearing,
                mea,
                Math.max(1e-9, hitboxDistance),
                activeShadowGfIntervals,
                distribution);

        List<Double> minima = new ArrayList<>();
        minima.add(clampedMin);
        minima.add(clampedMax);
        for (double[] shadow : activeShadowGfIntervals) {
            if (shadow[0] >= clampedMin && shadow[0] <= clampedMax) {
                appendUniqueByEpsilon(minima, shadow[0], GF_DUPLICATE_EPS);
            }
            if (shadow[1] >= clampedMin && shadow[1] <= clampedMax) {
                appendUniqueByEpsilon(minima, shadow[1], GF_DUPLICATE_EPS);
            }
        }
        double gapCursor = clampedMin;
        for (double[] shadow : activeShadowGfIntervals) {
            double shadowStart = Math.max(clampedMin, shadow[0]);
            double shadowEnd = Math.min(clampedMax, shadow[1]);
            if (shadowEnd <= gapCursor) {
                continue;
            }
            if (shadowStart > gapCursor + GF_DUPLICATE_EPS) {
                appendUniqueByEpsilon(minima, (gapCursor + shadowStart) * 0.5, GF_DUPLICATE_EPS);
            }
            gapCursor = Math.max(gapCursor, shadowEnd);
            if (gapCursor >= clampedMax) {
                break;
            }
        }
        if (gapCursor < clampedMax - GF_DUPLICATE_EPS) {
            appendUniqueByEpsilon(minima, (gapCursor + clampedMax) * 0.5, GF_DUPLICATE_EPS);
        }

        double scanStep = (clampedMax - clampedMin) / BotConfig.Movement.GF_EXTREMA_SCAN_STEPS;
        double prevGf = clampedMin;
        double prevDerivative = dangerDerivativeAtGf(dangerContext, prevGf);
        for (int i = 1; i <= BotConfig.Movement.GF_EXTREMA_SCAN_STEPS; i++) {
            double gf = (i == BotConfig.Movement.GF_EXTREMA_SCAN_STEPS)
                    ? clampedMax
                    : clampedMin + i * scanStep;
            double derivative = dangerDerivativeAtGf(dangerContext, gf);

            // Negative->positive derivative crossing indicates a local minimum.
            if (prevDerivative < 0.0 && derivative >= 0.0) {
                double seed = (prevGf + gf) * 0.5;
                double localMin = refineLocalMinimum(dangerContext, seed);
                appendUniqueByEpsilon(minima, localMin, GF_DUPLICATE_EPS);
            }

            prevGf = gf;
            prevDerivative = derivative;
        }

        if (minima.size() < k) {
            // Backstop for near-unimodal curves: add a small deterministic grid so
            // we can still return k path branches while keeping minima preferred.
            int supplementalSamples = Math.max(8, k * 3);
            for (int i = 0; i <= supplementalSamples; i++) {
                double gf = clampedMin + (clampedMax - clampedMin) * i / supplementalSamples;
                appendUniqueByEpsilon(minima, gf, GF_DUPLICATE_EPS);
            }
        }

        // Score each candidate minimum by danger and sort by safest first.
        List<CandidateDanger> scoredCandidates = new ArrayList<>(minima.size());
        for (int i = 0; i < minima.size(); i++) {
            double gf = minima.get(i);
            scoredCandidates.add(new CandidateDanger(gf, dangerAtGf(dangerContext, gf)));
        }
        scoredCandidates.sort(Comparator.comparingDouble(candidate -> candidate.danger));

        // Keep separation to preserve strategic diversity and to guard against
        // near-duplicate extrema from finite-difference noise.
        double minGfSeparation = minimumGfSeparation(dangerContext);
        List<Double> selected = new ArrayList<>();
        for (CandidateDanger candidate : scoredCandidates) {
            if (selected.size() >= k) {
                break;
            }
            boolean tooClose = false;
            for (double prev : selected) {
                if (Math.abs(candidate.gf - prev) < minGfSeparation) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                selected.add(candidate.gf);
            }
        }

        // If there are fewer than k separated minima, fill from remaining extrema.
        // This keeps branching width stable even on near-unimodal danger curves.
        if (selected.size() < k) {
            appendFallbackCandidates(selected, scoredCandidates, k, dangerContext, minGfSeparation);
            for (CandidateDanger candidate : scoredCandidates) {
                if (selected.size() >= k) {
                    break;
                }
                appendUniqueByEpsilon(selected, candidate.gf, GF_DUPLICATE_EPS);
            }
        }

        double[] result = new double[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            result[i] = selected.get(i);
        }
        return result;
    }

    private double dangerAtGf(DangerCurveContext context, double gf) {
        double[] gfInterval = exactHitboxGfInterval(context, gf);
        double start = gfInterval[0];
        double end = gfInterval[1];
        if (end <= start) {
            return 0.0;
        }
        if (context.shadowGfIntervals == null || context.shadowGfIntervals.isEmpty()) {
            return context.distribution.integrate(start, end);
        }

        // shadowGfIntervals is expected to be merged and sorted.
        double cursor = start;
        double danger = 0.0;
        for (double[] shadow : context.shadowGfIntervals) {
            double shadowStart = shadow[0];
            double shadowEnd = shadow[1];
            if (shadowEnd <= cursor) {
                continue;
            }
            if (shadowStart >= end) {
                break;
            }
            if (shadowStart > cursor) {
                danger += context.distribution.integrate(cursor, Math.min(shadowStart, end));
            }
            cursor = Math.max(cursor, shadowEnd);
            if (cursor >= end) {
                return danger;
            }
        }

        if (cursor < end) {
            danger += context.distribution.integrate(cursor, end);
        }
        return danger;
    }

    private static double minimumGfSeparation(DangerCurveContext context) {
        double reachableWidth = Math.max(0.0, context.maxGf - context.minGf);
        return Math.max(
                GF_DUPLICATE_EPS,
                BotConfig.Movement.MIN_GF_SEPARATION_FRACTION * reachableWidth);
    }

    private void appendFallbackCandidates(List<Double> selected,
                                          List<CandidateDanger> scoredCandidates,
                                          int k,
                                          DangerCurveContext context,
                                          double minGfSeparation) {
        if (selected.size() >= k || scoredCandidates.isEmpty()) {
            return;
        }
        double bestGf = scoredCandidates.get(0).gf;
        if (hasOriginalCandidateOutsideBestBand(scoredCandidates, bestGf, minGfSeparation)) {
            return;
        }

        List<CandidateDanger> boundaryCandidates = boundaryFallbackCandidates(bestGf, context, minGfSeparation);
        boundaryCandidates.sort(Comparator.comparingDouble(candidate -> candidate.danger));
        for (CandidateDanger candidate : boundaryCandidates) {
            if (selected.size() >= k) {
                break;
            }
            appendUniqueByEpsilon(selected, candidate.gf, GF_DUPLICATE_EPS);
        }
    }

    private static boolean hasOriginalCandidateOutsideBestBand(List<CandidateDanger> scoredCandidates,
                                                               double bestGf,
                                                               double minGfSeparation) {
        for (int i = 1; i < scoredCandidates.size(); i++) {
            if (Math.abs(scoredCandidates.get(i).gf - bestGf) >= minGfSeparation) {
                return true;
            }
        }
        return false;
    }

    private List<CandidateDanger> boundaryFallbackCandidates(double bestGf,
                                                             DangerCurveContext context,
                                                             double minGfSeparation) {
        List<CandidateDanger> boundaries = new ArrayList<>(2);
        double lowerBoundary = Math.max(context.minGf, bestGf - minGfSeparation);
        double upperBoundary = Math.min(context.maxGf, bestGf + minGfSeparation);
        if (lowerBoundary < bestGf - GF_DUPLICATE_EPS) {
            boundaries.add(new CandidateDanger(lowerBoundary, dangerAtGf(context, lowerBoundary)));
        }
        if (upperBoundary > bestGf + GF_DUPLICATE_EPS) {
            boundaries.add(new CandidateDanger(upperBoundary, dangerAtGf(context, upperBoundary)));
        }
        return boundaries;
    }

    private static double[] exactHitboxGfInterval(DangerCurveContext context, double gf) {
        double absoluteBearing = MathUtils.gfToAngle(context.referenceBearing, gf, context.mea);
        double[] offsets = RobotHitbox.guessFactorIntervalOffsets(
                absoluteBearing,
                context.hitboxDistance,
                context.mea);
        double start = gf + offsets[0];
        double end = gf + offsets[1];
        if (end < start) {
            throw new IllegalStateException(
                    "Exact hitbox GF interval must be ordered: start=" + start + ", end=" + end);
        }
        return new double[]{start, end};
    }

    private double dangerDerivativeAtGf(DangerCurveContext context, double gf) {
        double width = Math.max(1e-6, context.maxGf - context.minGf);
        double h = Math.min(1e-2, width / 40.0);
        double left = Math.max(context.minGf, gf - h);
        double right = Math.min(context.maxGf, gf + h);
        if (right <= left) {
            return 0.0;
        }
        return (dangerAtGf(context, right) - dangerAtGf(context, left)) / (right - left);
    }

    private double refineLocalMinimum(DangerCurveContext context, double seed) {
        double gf = Math.max(context.minGf, Math.min(context.maxGf, seed));
        double width = Math.max(1e-6, context.maxGf - context.minGf);
        double step = Math.min(0.08, width / 8.0);
        double bestDanger = dangerAtGf(context, gf);

        for (int i = 0; i < 24; i++) {
            double derivative = dangerDerivativeAtGf(context, gf);
            if (Math.abs(derivative) < 1e-7 || step < 1e-4) {
                break;
            }

            double direction = -Math.signum(derivative);
            double candidate = Math.max(context.minGf, Math.min(context.maxGf, gf + direction * step));
            double candidateDanger = dangerAtGf(context, candidate);
            if (candidateDanger < bestDanger) {
                gf = candidate;
                bestDanger = candidateDanger;
                step *= 1.1;
            } else {
                step *= 0.5;
            }
        }

        return gf;
    }

    private static void appendUniqueByEpsilon(List<Double> values, double candidate, double epsilon) {
        for (double value : values) {
            if (Math.abs(value - candidate) <= epsilon) {
                return;
            }
        }
        values.add(candidate);
    }
}
