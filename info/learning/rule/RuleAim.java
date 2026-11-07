package oog.mega.saguaro.info.learning.rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.learning.rule.WaveReachability.Position;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.MathUtils;
import robocode.util.Utils;

/**
 * Where the opponent will be when a shot of ours reaches it, as the targeting rule
 * model predicts it: AntiSurfer's gun, ported.
 *
 * The opponent can reach a band of crossings on the shot from its last scan -- bounded
 * analytically from its scanned position and velocity under Robocode's own motion rules
 * -- and its choice among them is scored by the rating it holds now, as a probability in
 * exactly the form the model is fitted in.
 *
 * AntiSurfer also chained the opponent through the waves we already have in flight,
 * keeping its likeliest few crossings of each. That was measured out (October 30): with
 * it, our hit rate was no better at matched power, the opponents hit us more against
 * each of DrussGT, Shadow and Needle, and the shot chances it predicted ran half again
 * above the realised rate -- three adjacent likeliest cells renormalised to certainty
 * made the opponent look pinned. It also cost a quarter of the CPU.
 *
 * AntiSurfer also kept a bank of fixed surfing controllers simulated against the rating,
 * the best-predicting one offering a point whose share of the distribution was learned.
 * That was measured out too (October 31): its learned share stayed between 0.7% and 3% of
 * the probability against DrussGT, BeepBoop, FretNano and Piyane, while it cost a quarter
 * of Saguaro's CPU. The density is the distribution Saguaro aims from.
 */
final class RuleAim {
    private static final int BINS = RuleModel.BINS;
    private static final double SPACING = 2.0 / (BINS - 1);
    private static final double[] GRID = new double[BINS];
    static {
        for (int i = 0; i < BINS; i++) GRID[i] = RuleModel.gridPoint(i);
    }

    /** The battle as the gun reads it this tick. */
    static final class Scene {
        final long now;
        final Position enemy;
        final ChainWave.Mover mover;
        final double selfX, selfY, width, height;
        Scene(long now, Position enemy, ChainWave.Mover mover, double selfX, double selfY,
              double width, double height) {
            this.now = now; this.enemy = enemy; this.mover = mover; this.selfX = selfX; this.selfY = selfY;
            this.width = width; this.height = height;
        }
    }

    /** What a shot of ours was aimed from, kept until its crossing is seen. */
    static final class Record {
        final double[] masses;
        final double reachLower, reachUpper;
        Record(double[] masses, double reachLower, double reachUpper) {
            this.masses = masses; this.reachLower = reachLower; this.reachUpper = reachUpper;
        }
    }

    /** A gun distribution's raw cell masses and the reach rated. */
    static final class Prediction {
        final double[] masses;
        final double reachLower, reachUpper;
        Prediction(double[] masses, double reachLower, double reachUpper) {
            this.masses = masses; this.reachLower = reachLower; this.reachUpper = reachUpper;
        }
    }

    private final RuleModel model;
    private final RuleLog log;
    /** Keyed by Saguaro's own wave object. */
    private final Map<Object, Record> records = new IdentityHashMap<>();

    private long cacheTime = Long.MIN_VALUE;
    /**
     * A shot as the planner's queries share it: its fire tick and speed. The planner asks
     * about the same shot from every candidate path, differing only in where along each
     * path it is fired from -- a few pixels of range. The opponent's rating for the shot is
     * computed once per shot and shared; each query keeps its own exact reach and frame. A
     * CPU approximation: without it a tick's queries cost more than the tick.
     */
    private record ShotKey(long fireTime, double speed) { }
    private final Map<ShotKey, double[]> ratingCache = new HashMap<>();
    private final Map<ShotKey, double[]> evidenceCache = new HashMap<>();

    /** Diagnostics. */
    long terminalRejected, terminalRequests, terminalNanos;

    RuleAim(RuleModel model, RuleLog log) {
        this.model = model;
        this.log = log;
    }

    void startBattle() {
        startRound();
    }

    void startRound() {
        records.clear();
        ratingCache.clear();
        evidenceCache.clear();
        cacheTime = Long.MIN_VALUE;
    }

    private void tick(long now) {
        if (cacheTime == now) return;
        cacheTime = now;
        ratingCache.clear();
        evidenceCache.clear();
    }

    // ---- the rating ---------------------------------------------------------------

    /** The opponent's rating over the whole grid for a situation. */
    private double[] rating(double[] features, long asOf) {
        double[] out = new double[BINS];
        model.rating(features, log.entries(), GRID, BINS, out, asOf);
        return out;
    }

    /**
     * Where the opponent crosses within an angular interval about a bearing on a wave, in
     * exactly the form the model is fitted in: the rating standardised over the cells the
     * interval reaches, p proportional to exp(-beta * z), mixed with the learned floor
     * spread uniformly over the interval. Returned as (angle, mass) per piece; each piece
     * lies within one cell.
     */
    private List<double[]> learned(ChainWave wave, double bearing, double lower, double upper,
                                   double[] bins, double[] evidence) {
        List<Double> cuts = new ArrayList<>();
        cuts.add(lower);
        cuts.add(upper);
        if (upper > lower) {
            for (int k = 1; k < BINS; k++) {
                double boundary = -1 + (k - 0.5) * SPACING;
                double offset = Utils.normalRelativeAngle(wave.angle(boundary) - bearing);
                if (offset > lower && offset < upper) cuts.add(offset);
            }
            java.util.Collections.sort(cuts);
        }
        // Each piece: its middle offset, its angular measure, its rating.
        List<double[]> pieces = new ArrayList<>();
        boolean[] reached = new boolean[BINS];
        if (!(upper > lower)) {
            int cell = wave.cell(bearing + lower);
            reached[cell] = true;
            pieces.add(new double[]{lower, 1, bins[cell]});
        } else {
            for (int i = 0; i + 1 < cuts.size(); i++) {
                double lo = cuts.get(i), hi = cuts.get(i + 1);
                if (!(hi > lo)) continue;
                int cell = wave.cell(bearing + (lo + hi) * 0.5);
                reached[cell] = true;
                pieces.add(new double[]{(lo + hi) * 0.5, hi - lo, bins[cell]});
            }
        }
        if (pieces.isEmpty()) return pieces;
        double sum = 0, square = 0, minimum = Double.POSITIVE_INFINITY;
        int count = 0;
        for (int i = 0; i < BINS; i++) {
            if (!reached[i]) continue;
            sum += bins[i];
            square += bins[i] * bins[i];
            count++;
        }
        for (double[] piece : pieces) minimum = Math.min(minimum, piece[2]);
        double evidenceSum = 0, evidenceSquare = 0;
        for (int i = 0; i < BINS; i++) {
            if (!reached[i]) continue;
            evidenceSum += evidence[i];
            evidenceSquare += evidence[i] * evidence[i];
        }
        double evidenceMean = evidenceSum / count;
        double mean = sum / count, spread = Math.sqrt(Math.max(0, square / count - mean * mean))
                + RuleModel.EVIDENCE_SHARE * Math.sqrt(Math.max(0, evidenceSquare / count - evidenceMean * evidenceMean));
        double beta = model.choiceSharpness(), floor = model.floor();
        double rated = 0, measured = 0;
        for (double[] piece : pieces) {
            double measure = piece[1];
            piece[2] = measure * (spread > 1e-9 ? Math.exp(-beta * (piece[2] - minimum) / spread) : 1);
            rated += piece[2];
            measured += measure;
        }
        List<double[]> result = new ArrayList<>(pieces.size());
        for (double[] piece : pieces) {
            double mass = (1 - floor) * piece[2] / rated + floor * piece[1] / measured;
            if (mass > 0) result.add(new double[]{bearing + piece[0], mass});
        }
        return result;
    }

    // ---- reach ---------------------------------------------------------------------

    /**
     * Narrow an offset interval to the part reachable under Robocode's velocity rules,
     * given the velocity observed at the scan. An upper bound, never a prediction.
     */
    private static double[] physicalRange(InertialReachability area, double lower, double upper,
                                          Position anchor, double vx, double vy) {
        // The feasible set is an interval in practice; sixteen probes find it and eight
        // bisections pin each edge to a 4096th of the interval, about a tenth of a pixel at
        // range -- sixteen had pinned it to a millionth.
        final int probes = 16;
        int firstFeasible = -1, lastFeasible = -1;
        for (int step = 0; step <= probes; step++) {
            double offset = lower + (upper - lower) * step / (double) probes;
            if (physicallyReachable(area, offset, anchor, vx, vy)) {
                if (firstFeasible < 0) firstFeasible = step;
                lastFeasible = step;
            }
        }
        if (firstFeasible < 0) return null;
        double width = (upper - lower) / probes;
        double low = lower + width * firstFeasible, high = lower + width * lastFeasible;
        if (firstFeasible > 0) {
            double bad = low - width;
            for (int i = 0; i < 8; i++) {
                double mid = (low + bad) * .5;
                if (physicallyReachable(area, mid, anchor, vx, vy)) low = mid; else bad = mid;
            }
        }
        if (lastFeasible < probes) {
            double bad = high + width;
            for (int i = 0; i < 8; i++) {
                double mid = (high + bad) * .5;
                if (physicallyReachable(area, mid, anchor, vx, vy)) high = mid; else bad = mid;
            }
        }
        return new double[]{low, high};
    }

    private static boolean physicallyReachable(InertialReachability area, double offset, Position anchor,
                                               double vx, double vy) {
        Position point = area.pointAt(area.bearing + offset);
        if (point == null) return false;
        double dx = point.x() - anchor.x(), dy = point.y() - anchor.y();
        double distance = oog.mega.saguaro.math.MathUtils.hypot(dx, dy);
        if (distance <= 1e-9) return true;
        double projection = (vx * dx + vy * dy) / distance;
        return distance <= PhysicalReach.maxDisplacement(projection, point.time() - anchor.time()) + 1e-9;
    }

    /**
     * The interval of offsets about the shot's bearing the opponent can reach on it from
     * its last scan, within the shot's in-field escape range, or null.
     */
    private static double[] reach(ChainWave shot, Scene scene, double vx, double vy,
                                  double fullLower, double fullUpper) {
        Position anchor = scene.enemy;
        if (!WaveReachability.feasible(shot.originX, shot.originY, shot.speed, shot.fireTime, anchor)) return null;
        WaveReachability constraint = InertialReachability.initialConstraint(shot.originX, shot.originY, shot.speed,
                shot.fireTime, anchor, vx, vy, shot.originX, shot.originY, scene.width, scene.height);
        InertialReachability area = new InertialReachability(shot.originX, shot.originY, shot.speed, shot.fireTime,
                anchor, scene.width, scene.height, constraint);
        if (area.isEmpty()) return null;
        double[] physical = physicalRange(area, area.minOffset, area.maxOffset, anchor, vx, vy);
        if (physical == null) return null;
        double shift = Utils.normalRelativeAngle(area.bearing - shot.referenceBearing);
        double lower = Math.max(fullLower, shift + physical[0]);
        double upper = Math.min(fullUpper, shift + physical[1]);
        if (lower > upper) return null;
        return new double[]{lower, upper};
    }

    // ---- a new shot ----------------------------------------------------------------

    /**
     * The opponent's crossing distribution on a new shot, as raw cell masses in the
     * shot's canonical frame, with the reach it was rated over. Null when the opponent's
     * reach on the shot cannot be bounded.
     *
     * @param shot the prospective wave, fired at its context's time
     */
    Prediction predict(Scene scene, ChainWave shot, boolean useModel) {
        long t0 = System.nanoTime();
        tick(scene.now);
        WaveContextFeatures.WaveContext context = shot.context;
        double[] escape = MathUtils.inFieldMaxEscapeGfRange(shot.originX, shot.originY,
                context.targetX, context.targetY, shot.speed, scene.width, scene.height);
        double vx = Math.sin(scene.mover.heading()) * scene.mover.velocity();
        double vy = Math.cos(scene.mover.heading()) * scene.mover.velocity();
        double bearing = shot.referenceBearing;
        terminalRequests++;
        double[] interval = reach(shot, scene, vx, vy, escape[0] * shot.mea, escape[1] * shot.mea);
        if (interval == null) { terminalRejected++; return null; }
        double a = shot.gf(bearing + interval[0]), b = shot.gf(bearing + interval[1]);
        double lower = Math.min(a, b), upper = Math.max(a, b);
        if (!useModel) return new Prediction(null, lower, upper);

        ShotKey key = new ShotKey(shot.fireTime, shared(shot.speed));
        double[] bins = ratingCache.get(key);
        if (bins == null) {
            bins = rating(RuleProfile.features(context), log.clock(context.battleTime));
            ratingCache.put(key, bins);
            double[] evidence = new double[BINS];
            model.evidence(log.entries(), GRID, BINS, evidence);
            evidenceCache.put(key, evidence);
        }
        double[] density = new double[BINS];
        for (double[] piece : learned(shot, bearing, interval[0], interval[1], bins, evidenceCache.get(key))) {
            density[shot.cell(piece[0])] += piece[1];
        }
        terminalNanos += System.nanoTime() - t0;
        return new Prediction(density, lower, upper);
    }

    /** A bullet speed as shared between nearby powers: to the nearest pixel a tick, a third of a power. */
    private static double shared(double speed) {
        return Math.round(speed);
    }

    // ---- learning from our shots ---------------------------------------------------

    /** A shot of ours was fired: keep what it was aimed from until its crossing is seen. */
    void onFired(Object key, Scene scene, ChainWave wave, boolean useModel) {
        Prediction prediction = predict(scene, wave, useModel);
        if (prediction == null) return;
        records.put(key, new Record(prediction.masses, prediction.reachLower, prediction.reachUpper));
    }

    Record take(Object key) { return records.remove(key); }
}
