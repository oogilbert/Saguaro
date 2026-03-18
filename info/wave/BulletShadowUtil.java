package oog.mega.saguaro.info.wave;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;

/**
 * Utilities for projecting bullet-shadow intersections between our bullets and enemy waves.
 */
public final class BulletShadowUtil {
    private static final double ROOT_EPSILON = 1e-9;
    private static final double ANGLE_EPSILON = 1e-4;
    private static final double WEIGHT_EPSILON = 1e-9;

    private BulletShadowUtil() {
        // Utility class
    }

    /**
     * Shadow interval on an enemy wave, represented as an absolute-angle range
     * around the enemy-wave origin. The interval becomes active on activationTime.
     */
    public static class ShadowInterval {
        public final long activationTime;
        public final double startAngle;
        public final double endAngle;
        public final double weight;

        public ShadowInterval(long activationTime, double startAngle, double endAngle, double weight) {
            if (endAngle < startAngle) {
                throw new IllegalStateException(
                        "ShadowInterval requires startAngle <= endAngle: "
                                + startAngle + " to " + endAngle);
            }
            if (!Double.isFinite(weight) || weight <= 0.0 || weight > 1.0 + WEIGHT_EPSILON) {
                throw new IllegalStateException("ShadowInterval requires 0 < weight <= 1: " + weight);
            }
            this.activationTime = activationTime;
            this.startAngle = startAngle;
            this.endAngle = endAngle;
            this.weight = Math.min(1.0, Math.max(0.0, weight));
        }
    }

    /**
     * GF interval carrying a piecewise-constant weight over that span.
     */
    public static class WeightedGfInterval {
        public final double startGf;
        public final double endGf;
        public final double weight;

        public WeightedGfInterval(double startGf, double endGf, double weight) {
            if (endGf < startGf) {
                throw new IllegalStateException(
                        "WeightedGfInterval requires startGf <= endGf: "
                                + startGf + " to " + endGf);
            }
            if (!Double.isFinite(weight) || weight <= 0.0 || weight > 1.0 + WEIGHT_EPSILON) {
                throw new IllegalStateException("WeightedGfInterval requires 0 < weight <= 1: " + weight);
            }
            this.startGf = startGf;
            this.endGf = endGf;
            this.weight = Math.min(1.0, Math.max(0.0, weight));
        }
    }

    /**
     * Builds shadow intervals on {@code enemyWave} from all currently active
     * friendly waves in {@code myWaves}.
     */
    public static List<ShadowInterval> buildShadowsOnEnemyWave(Wave enemyWave, List<Wave> myWaves) {
        List<ShadowInterval> shadows = new ArrayList<>();
        for (Wave myWave : myWaves) {
            addWavePairShadows(shadows, enemyWave, myWave);
        }
        return shadows;
    }

    /**
     * Builds shadow intervals on {@code enemyWave} from a single friendly wave.
     */
    public static List<ShadowInterval> buildShadowsOnEnemyWave(Wave enemyWave, Wave myWave) {
        List<ShadowInterval> shadows = new ArrayList<>(6);
        addWavePairShadows(shadows, enemyWave, myWave);
        return shadows;
    }

    /**
     * Converts active shadow angle intervals into weighted GF intervals for a
     * given wave reference bearing and MEA.
     */
    public static List<WeightedGfInterval> activeGfIntervals(List<ShadowInterval> shadows,
                                                             long time,
                                                             double referenceBearing,
                                                             double mea) {
        if (mea <= 0.0) {
            throw new IllegalStateException("MEA must be > 0 to convert shadow intervals: " + mea);
        }

        List<WeightedGfInterval> gfIntervals = new ArrayList<>();
        for (ShadowInterval shadow : shadows) {
            if (shadow.activationTime > time) {
                continue;
            }
            addGfIntervalsForShadow(gfIntervals, shadow, referenceBearing, mea);
        }
        return gfIntervals;
    }

    /**
     * Converts all shadow angle intervals into weighted GF intervals,
     * regardless of activation time.
     */
    public static List<WeightedGfInterval> allGfIntervals(List<ShadowInterval> shadows,
                                                          double referenceBearing,
                                                          double mea) {
        if (mea <= 0.0) {
            throw new IllegalStateException("MEA must be > 0 to convert shadow intervals: " + mea);
        }

        List<WeightedGfInterval> gfIntervals = new ArrayList<>();
        for (ShadowInterval shadow : shadows) {
            addGfIntervalsForShadow(gfIntervals, shadow, referenceBearing, mea);
        }
        return gfIntervals;
    }

    private static void addGfIntervalsForShadow(List<WeightedGfInterval> out,
                                                ShadowInterval shadow,
                                                double referenceBearing,
                                                double mea) {
        double width = shadow.endAngle - shadow.startAngle;
        if (!(width > 0.0)) {
            return;
        }

        // Preserve contiguous interval width relative to reference bearing.
        // Normalizing endpoints independently can inflate tiny wrap-crossing
        // intervals into near-full [-PI, PI] spans.
        double start = MathUtils.normalizeAngle(shadow.startAngle - referenceBearing);
        double end = start + width;
        if (end <= Math.PI) {
            out.add(new WeightedGfInterval(start / mea, end / mea, shadow.weight));
            return;
        }

        double twoPi = 2.0 * Math.PI;
        out.add(new WeightedGfInterval(start / mea, Math.PI / mea, shadow.weight));
        out.add(new WeightedGfInterval(-Math.PI / mea, (end - twoPi) / mea, shadow.weight));
    }

    /**
     * Subtracts a set of GF shadow intervals from one base GF interval.
     */
    public static List<double[]> subtractGfIntervals(double baseStart, double baseEnd, List<double[]> shadows) {
        List<double[]> remaining = new ArrayList<>();
        if (baseEnd <= baseStart) {
            return remaining;
        }
        remaining.add(new double[]{baseStart, baseEnd});

        for (double[] shadow : shadows) {
            double shadowStart = shadow[0];
            double shadowEnd = shadow[1];
            if (shadowEnd <= shadowStart) {
                continue;
            }

            List<double[]> next = new ArrayList<>();
            for (double[] segment : remaining) {
                double segStart = segment[0];
                double segEnd = segment[1];
                if (shadowEnd <= segStart || shadowStart >= segEnd) {
                    next.add(segment);
                    continue;
                }

                if (shadowStart > segStart) {
                    next.add(new double[]{segStart, Math.min(shadowStart, segEnd)});
                }
                if (shadowEnd < segEnd) {
                    next.add(new double[]{Math.max(shadowEnd, segStart), segEnd});
                }
            }
            remaining = next;
            if (remaining.isEmpty()) {
                return remaining;
            }
        }

        return remaining;
    }

    /**
     * Subtracts a merged/sorted set of GF shadow intervals from one base GF
     * interval, appending the remaining segments into {@code out}.
     */
    public static void subtractMergedGfIntervalsInto(double baseStart, double baseEnd,
                                                     List<double[]> mergedShadows,
                                                     List<double[]> out) {
        if (baseEnd <= baseStart) {
            return;
        }

        double cursor = baseStart;
        for (double[] shadow : mergedShadows) {
            double shadowStart = shadow[0];
            double shadowEnd = shadow[1];
            if (shadowEnd <= shadowStart) {
                continue;
            }
            if (shadowEnd <= cursor) {
                continue;
            }
            if (shadowStart >= baseEnd) {
                break;
            }
            if (shadowStart > cursor) {
                out.add(new double[]{cursor, Math.min(shadowStart, baseEnd)});
            }
            cursor = Math.max(cursor, shadowEnd);
            if (cursor >= baseEnd) {
                return;
            }
        }

        if (cursor < baseEnd) {
            out.add(new double[]{cursor, baseEnd});
        }
    }

    /**
     * Integrates a distribution over GF intervals, clipping to [minGf, maxGf]
     * and merging overlaps before integration.
     */
    public static double integrateMergedIntervals(GuessFactorDistribution distribution, List<double[]> intervals,
                                                  double minGf, double maxGf) {
        List<double[]> merged = mergeAndClipIntervals(intervals, minGf, maxGf);
        double mass = 0.0;
        for (double[] interval : merged) {
            mass += distribution.integrate(interval[0], interval[1]);
        }
        return mass;
    }

    /**
     * Integrates a distribution over weighted GF intervals after clipping and
     * merging them into disjoint weighted segments.
     */
    public static double integrateWeightedIntervals(GuessFactorDistribution distribution,
                                                    List<WeightedGfInterval> intervals,
                                                    double minGf,
                                                    double maxGf) {
        List<WeightedGfInterval> merged = mergeAndClipWeightedIntervals(intervals, minGf, maxGf);
        double mass = 0.0;
        for (WeightedGfInterval interval : merged) {
            mass += interval.weight * distribution.integrate(interval.startGf, interval.endGf);
        }
        return mass;
    }

    /**
     * Merges overlapping intervals after clipping to [minValue, maxValue].
     */
    public static List<double[]> mergeAndClipIntervals(List<double[]> intervals, double minValue, double maxValue) {
        List<double[]> clipped = new ArrayList<>();
        for (double[] interval : intervals) {
            double start = Math.max(minValue, interval[0]);
            double end = Math.min(maxValue, interval[1]);
            if (end > start) {
                clipped.add(new double[]{start, end});
            }
        }
        if (clipped.isEmpty()) {
            return clipped;
        }

        clipped.sort(Comparator.comparingDouble(a -> a[0]));

        List<double[]> merged = new ArrayList<>();
        double currentStart = clipped.get(0)[0];
        double currentEnd = clipped.get(0)[1];
        for (int i = 1; i < clipped.size(); i++) {
            double start = clipped.get(i)[0];
            double end = clipped.get(i)[1];
            if (start <= currentEnd) {
                currentEnd = Math.max(currentEnd, end);
            } else {
                merged.add(new double[]{currentStart, currentEnd});
                currentStart = start;
                currentEnd = end;
            }
        }
        merged.add(new double[]{currentStart, currentEnd});
        return merged;
    }

    /**
     * Merges weighted intervals into disjoint clipped segments, combining
     * overlapping weights additively and clamping the result to 1.0.
     */
    public static List<WeightedGfInterval> mergeAndClipWeightedIntervals(List<WeightedGfInterval> intervals,
                                                                         double minValue,
                                                                         double maxValue) {
        List<WeightedBoundaryEvent> events = new ArrayList<>(intervals.size() * 2);
        for (WeightedGfInterval interval : intervals) {
            double start = Math.max(minValue, interval.startGf);
            double end = Math.min(maxValue, interval.endGf);
            if (!(end > start) || interval.weight <= WEIGHT_EPSILON) {
                continue;
            }
            double clampedWeight = Math.min(1.0, Math.max(0.0, interval.weight));
            events.add(new WeightedBoundaryEvent(start, clampedWeight));
            events.add(new WeightedBoundaryEvent(end, -clampedWeight));
        }
        if (events.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        events.sort(Comparator.comparingDouble(e -> e.position));

        List<WeightedGfInterval> merged = new ArrayList<>();
        int i = 0;
        double rawCurrentWeight = 0.0;
        while (i < events.size()) {
            double position = events.get(i).position;
            double delta = 0.0;
            while (i < events.size() && Math.abs(events.get(i).position - position) <= ROOT_EPSILON) {
                delta += events.get(i).deltaWeight;
                i++;
            }
            rawCurrentWeight = Math.max(0.0, rawCurrentWeight + delta);
            if (i >= events.size()) {
                break;
            }
            double nextPosition = events.get(i).position;
            double segmentWeight = Math.min(1.0, rawCurrentWeight);
            if (nextPosition > position && segmentWeight > WEIGHT_EPSILON) {
                appendWeightedInterval(merged, position, nextPosition, segmentWeight);
            }
        }
        return merged;
    }

    /**
     * Appends the complement of merged shadow coverage across one base interval
     * as weighted exposure segments.
     */
    public static void appendWeightedComplementOfMergedIntervals(double baseStart,
                                                                 double baseEnd,
                                                                 List<WeightedGfInterval> mergedShadows,
                                                                 List<WeightedGfInterval> out) {
        if (!(baseEnd > baseStart)) {
            return;
        }
        double cursor = baseStart;
        for (WeightedGfInterval shadow : mergedShadows) {
            if (shadow.endGf <= cursor) {
                continue;
            }
            if (shadow.startGf >= baseEnd) {
                break;
            }

            if (shadow.startGf > cursor) {
                appendWeightedInterval(out, cursor, Math.min(shadow.startGf, baseEnd), 1.0);
            }

            double overlapStart = Math.max(cursor, shadow.startGf);
            double overlapEnd = Math.min(baseEnd, shadow.endGf);
            if (overlapEnd > overlapStart) {
                double exposureWeight = 1.0 - shadow.weight;
                if (exposureWeight > WEIGHT_EPSILON) {
                    appendWeightedInterval(out, overlapStart, overlapEnd, exposureWeight);
                }
                cursor = overlapEnd;
                if (cursor >= baseEnd) {
                    return;
                }
            }
        }

        if (cursor < baseEnd) {
            appendWeightedInterval(out, cursor, baseEnd, 1.0);
        }
    }

    private static void addWavePairShadows(List<ShadowInterval> out, Wave enemyWave, Wave myWave) {
        if (myWave == null || enemyWave == null) {
            return;
        }
        if (myWave.isEnemy || !enemyWave.isEnemy) {
            return;
        }
        if (!Double.isFinite(myWave.heading)) {
            return;
        }

        double enemySpeed = enemyWave.speed;
        double mySpeed = myWave.speed;

        double vx = FastTrig.sin(myWave.heading) * mySpeed;
        double vy = FastTrig.cos(myWave.heading) * mySpeed;

        double rx = myWave.originX - enemyWave.originX;
        double ry = myWave.originY - enemyWave.originY;

        double delta = myWave.fireTime - enemyWave.fireTime;
        double a = mySpeed * mySpeed - enemySpeed * enemySpeed;
        double b = 2.0 * (rx * vx + ry * vy - enemySpeed * enemySpeed * delta);
        double c = rx * rx + ry * ry - enemySpeed * enemySpeed * delta * delta;

        double dtMin = Math.max(0.0, enemyWave.fireTime - myWave.fireTime);
        if (Math.abs(a) < ROOT_EPSILON) {
            if (Math.abs(b) < ROOT_EPSILON) {
                return;
            }
            double root = -c / b;
            addShadowAtRoot(out, enemyWave, myWave, root, dtMin);
            return;
        }

        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < -ROOT_EPSILON) {
            return;
        }
        if (discriminant < 0.0) {
            discriminant = 0.0;
        }

        double sqrtDisc = Math.sqrt(discriminant);
        double root1 = (-b - sqrtDisc) / (2.0 * a);
        double root2 = (-b + sqrtDisc) / (2.0 * a);
        addShadowAtRoot(out, enemyWave, myWave, root1, dtMin);
        if (Math.abs(root2 - root1) > ROOT_EPSILON) {
            addShadowAtRoot(out, enemyWave, myWave, root2, dtMin);
        }
    }

    private static void addShadowAtRoot(List<ShadowInterval> out, Wave enemyWave, Wave myWave,
                                        double rootDt, double dtMin) {
        if (!Double.isFinite(rootDt) || rootDt < dtMin - ROOT_EPSILON) {
            return;
        }

        double absoluteTime = myWave.fireTime + rootDt;
        if (!Double.isFinite(absoluteTime)) {
            return;
        }

        long activationTick = (long) Math.ceil(absoluteTime - ROOT_EPSILON);
        long minActiveTick = Math.max(enemyWave.fireTime, myWave.fireTime);
        if (activationTick < minActiveTick) {
            activationTick = minActiveTick;
        }

        addShadowForUpdateOrderCase(out, enemyWave, myWave, activationTick, 0, 0, 1.0);
        addShadowForUpdateOrderCase(out, enemyWave, myWave, activationTick, -1, 0, 0.5);
        addShadowForUpdateOrderCase(out, enemyWave, myWave, activationTick, 0, -1, 0.5);
    }

    private static double[] myBulletPointAt(Wave myWave, long time) {
        long traveledTicks = Math.max(0L, time - myWave.fireTime);
        double distance = myWave.speed * traveledTicks;
        double x = myWave.originX + FastTrig.sin(myWave.heading) * distance;
        double y = myWave.originY + FastTrig.cos(myWave.heading) * distance;
        return new double[]{x, y};
    }

    private static void addShadowForUpdateOrderCase(List<ShadowInterval> out,
                                                    Wave enemyWave,
                                                    Wave myWave,
                                                    long activationTick,
                                                    int bulletOffset,
                                                    int waveOffset,
                                                    double weight) {
        long bulletStartTick = activationTick + bulletOffset;
        long bulletEndTick = bulletStartTick + 1L;
        long waveStartTick = activationTick + waveOffset;
        long waveEndTick = waveStartTick + 1L;
        if (bulletStartTick <= myWave.fireTime || waveStartTick <= enemyWave.fireTime) {
            return;
        }

        double[] bulletStart = myBulletPointAt(myWave, bulletStartTick);
        double[] bulletEnd = myBulletPointAt(myWave, bulletEndTick);
        double startDistance = Math.hypot(bulletStart[0] - enemyWave.originX, bulletStart[1] - enemyWave.originY);
        double endDistance = Math.hypot(bulletEnd[0] - enemyWave.originX, bulletEnd[1] - enemyWave.originY);
        double startRadius = (waveStartTick - enemyWave.fireTime) * enemyWave.speed;
        double endRadius = (waveEndTick - enemyWave.fireTime) * enemyWave.speed;
        if (startDistance < startRadius || endDistance > startDistance) {
            return;
        }
        if (endDistance > endRadius) {
            return;
        }

        double[] shadowStart = startDistance <= endRadius
                ? bulletStart
                : circleLineSegmentIntercept(
                        bulletStart[0], bulletStart[1],
                        bulletEnd[0], bulletEnd[1],
                        enemyWave.originX, enemyWave.originY,
                        endRadius,
                        true);
        double[] shadowEnd = endDistance >= startRadius
                ? bulletEnd
                : circleLineSegmentIntercept(
                        bulletStart[0], bulletStart[1],
                        bulletEnd[0], bulletEnd[1],
                        enemyWave.originX, enemyWave.originY,
                        startRadius,
                        false);
        if (shadowStart == null || shadowEnd == null) {
            return;
        }

        double startAngle = Math.atan2(shadowStart[0] - enemyWave.originX, shadowStart[1] - enemyWave.originY);
        double endAngle = Math.atan2(shadowEnd[0] - enemyWave.originX, shadowEnd[1] - enemyWave.originY);
        addAngleSweepInterval(out, activationTick, startAngle, endAngle, weight);
    }

    private static double[] circleLineSegmentIntercept(double startX,
                                                       double startY,
                                                       double endX,
                                                       double endY,
                                                       double circleX,
                                                       double circleY,
                                                       double radius,
                                                       boolean chooseNearestStart) {
        double dx = endX - startX;
        double dy = endY - startY;
        double fx = startX - circleX;
        double fy = startY - circleY;
        double a = dx * dx + dy * dy;
        if (a <= ROOT_EPSILON) {
            return null;
        }
        double b = 2.0 * (fx * dx + fy * dy);
        double c = fx * fx + fy * fy - radius * radius;
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < -ROOT_EPSILON) {
            return null;
        }
        if (discriminant < 0.0) {
            discriminant = 0.0;
        }
        double sqrtDisc = Math.sqrt(discriminant);
        double invDenominator = 0.5 / a;
        double t1 = (-b - sqrtDisc) * invDenominator;
        double t2 = (-b + sqrtDisc) * invDenominator;
        double chosenT = chooseNearestStart
                ? chooseSegmentRoot(t1, t2, true)
                : chooseSegmentRoot(t1, t2, false);
        if (Double.isNaN(chosenT)) {
            return null;
        }
        return new double[]{startX + dx * chosenT, startY + dy * chosenT};
    }

    private static double chooseSegmentRoot(double t1, double t2, boolean chooseNearestStart) {
        double best = Double.NaN;
        if (t1 >= -ROOT_EPSILON && t1 <= 1.0 + ROOT_EPSILON) {
            best = t1;
        }
        if (t2 >= -ROOT_EPSILON && t2 <= 1.0 + ROOT_EPSILON) {
            if (Double.isNaN(best) || (chooseNearestStart ? t2 < best : t2 > best)) {
                best = t2;
            }
        }
        if (Double.isNaN(best)) {
            return Double.NaN;
        }
        return Math.max(0.0, Math.min(1.0, best));
    }

    private static void addAngleSweepInterval(List<ShadowInterval> out, long activationTick,
                                              double angleA, double angleB, double weight) {
        double sweep = MathUtils.normalizeAngle(angleB - angleA);
        double start = angleA;
        double end = angleA + sweep;
        if (end < start) {
            double tmp = start;
            start = end;
            end = tmp;
        }

        start -= ANGLE_EPSILON;
        end += ANGLE_EPSILON;

        double twoPi = 2.0 * Math.PI;
        while (start < -Math.PI) {
            start += twoPi;
            end += twoPi;
        }
        while (start > Math.PI) {
            start -= twoPi;
            end -= twoPi;
        }

        if (end <= Math.PI) {
            out.add(new ShadowInterval(activationTick, start, end, weight));
            return;
        }

        out.add(new ShadowInterval(activationTick, start, Math.PI, weight));
        out.add(new ShadowInterval(activationTick, -Math.PI, end - twoPi, weight));
    }

    private static void appendWeightedInterval(List<WeightedGfInterval> out,
                                               double start,
                                               double end,
                                               double weight) {
        if (!(end > start) || weight <= WEIGHT_EPSILON) {
            return;
        }
        double clampedWeight = Math.min(1.0, Math.max(0.0, weight));
        if (!out.isEmpty()) {
            WeightedGfInterval previous = out.get(out.size() - 1);
            if (Math.abs(previous.endGf - start) <= ROOT_EPSILON
                    && Math.abs(previous.weight - clampedWeight) <= WEIGHT_EPSILON) {
                out.set(
                        out.size() - 1,
                        new WeightedGfInterval(previous.startGf, end, previous.weight));
                return;
            }
        }
        out.add(new WeightedGfInterval(start, end, clampedWeight));
    }

    private static final class WeightedBoundaryEvent {
        final double position;
        final double deltaWeight;

        WeightedBoundaryEvent(double position, double deltaWeight) {
            this.position = position;
            this.deltaWeight = deltaWeight;
        }
    }
}


