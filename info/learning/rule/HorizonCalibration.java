package oog.mega.saguaro.info.learning.rule;

import java.util.IdentityHashMap;
import java.util.Map;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.GuessFactorDistribution;

/**
 * Maps the danger a plan assigns an enemy wave, some ticks before the wave arrives, to
 * the hit chance actually realised from there.
 *
 * The planner scores each candidate as if it would follow that path, but it re-plans
 * every tick, so a wave still far away is met by later plans, not by this one. The
 * chosen plans' predictions show it: 10-19 ticks out they ran 1.7 times what followed,
 * and differences between them predicted about half the difference in outcome. Near
 * impact, with nothing left to re-plan, they were right on average. So the correction is
 * learned per horizon from the chosen plans' predictions and outcomes: one map at 10
 * ticks out and one at 20, none at arrival, linear in between and constant beyond 20.
 *
 * Each map takes the danger before any horizon correction, so the fit does not chase
 * its own output, and is applied to a wave's density at the scale of one body width, as
 * the dodge map is. Carried between battles like the dodge map: each battle starts from,
 * and is pulled toward, the corrections the last one settled on.
 */
public final class HorizonCalibration {
    public static final HorizonCalibration INSTANCE = new HorizonCalibration();

    private static final int NEAR = 10, FAR = 20;

    private final HitCalibration nearMap = new HitCalibration();
    private final HitCalibration farMap = new HitCalibration();
    /** Per enemy wave, the latest prediction made NEAR..FAR-1 and FAR+ ticks out; NaN where none. */
    private final Map<Wave, double[]> pending = new IdentityHashMap<>();

    private HorizonCalibration() { }

    public void startRound() { pending.clear(); }

    /** A new battle: back to no correction, until a saved one is imported. */
    void startBattle() {
        nearMap.clearPrior();
        farMap.clearPrior();
        pending.clear();
    }

    static final int EXPORTED_LENGTH = 4;

    /** Both corrections, near then far, for carrying to the next battle against this opponent. */
    double[] exportCorrections() {
        double[] near = nearMap.exportCorrection(), far = farMap.exportCorrection();
        return new double[]{near[0], near[1], far[0], far[1]};
    }

    void importCorrections(double[] corrections) {
        if (corrections == null || corrections.length != EXPORTED_LENGTH) return;
        nearMap.importCorrection(new double[]{corrections[0], corrections[1]});
        farMap.importCorrection(new double[]{corrections[2], corrections[3]});
    }

    /** The chosen plan's danger for a real enemy wave, before the horizon correction, this many ticks out. */
    public void notePlanned(Wave wave, long ticks, double prediction) {
        if (ticks < NEAR || !Double.isFinite(prediction)) return;
        double[] slots = pending.computeIfAbsent(wave, w -> new double[]{Double.NaN, Double.NaN});
        slots[ticks >= FAR ? 1 : 0] = prediction;
    }

    public void onResolved(Wave wave, boolean hit) {
        double[] slots = pending.remove(wave);
        if (slots == null) return;
        if (Double.isFinite(slots[0])) nearMap.observe(slots[0], hit);
        if (Double.isFinite(slots[1])) farMap.observe(slots[1], hit);
    }

    public void onCollided(Wave wave) { pending.remove(wave); }

    /** A wave's danger as seen this many ticks before it arrives; the base itself when nothing applies. */
    public GuessFactorDistribution distribution(GuessFactorDistribution base, Wave wave, double ticks) {
        if (base == null || wave.fireTimeContext == null || !(ticks > 0)) return base;
        double[] near = nearMap.exportCorrection(), far = farMap.exportCorrection();
        double c, d;
        if (ticks <= NEAR) {
            double t = ticks / NEAR;
            c = t * near[0]; d = t * near[1];
        } else if (ticks < FAR) {
            double t = (ticks - NEAR) / (FAR - NEAR);
            c = near[0] + t * (far[0] - near[0]); d = near[1] + t * (far[1] - near[1]);
        } else {
            c = far[0]; d = far[1];
        }
        if (c == 0 && d == 0) return base;
        return new Corrected(base, RuleProfile.bodyWidth(wave.fireTimeContext), c, d);
    }

    /** The danger a distribution assigns before any horizon correction. */
    public static GuessFactorDistribution uncorrected(GuessFactorDistribution distribution) {
        return distribution instanceof Corrected ? ((Corrected) distribution).base : distribution;
    }

    public String diagnostics() {
        double[] near = nearMap.exportCorrection(), far = farMap.exportCorrection();
        return String.format(java.util.Locale.ROOT,
                "horizon map %d ticks a=%.2f b=%.2f | %d+ ticks a=%.2f b=%.2f",
                NEAR, nearMap.intercept(), nearMap.slope(), FAR, farMap.intercept(), farMap.slope())
                + String.format(java.util.Locale.ROOT, " (c,d near %.2f,%.2f far %.2f,%.2f)", near[0], near[1], far[0], far[1]);
    }

    /** A wave's density passed through the horizon map, cell by cell of the rule model's grid. */
    private static final class Corrected implements GuessFactorDistribution {
        private static final int BINS = RuleModel.BINS;
        private static final double SPACING = 2.0 / (BINS - 1);

        final GuessFactorDistribution base;
        private final double[] density = new double[BINS];

        Corrected(GuessFactorDistribution base, double bodyWidth, double c, double d) {
            this.base = base;
            double width = Math.max(1e-3, bodyWidth);
            for (int i = 0; i < BINS; i++) {
                double cell = cellUpper(i) - cellLower(i);
                double q = base.integrate(cellLower(i), cellUpper(i)) / cell;
                density[i] = q > 0 ? HitCalibration.map(Math.min(0.999, q * width), c, d) / width : 0;
            }
        }

        private static double cellLower(int i) { return Math.max(-1, RuleModel.gridPoint(i) - SPACING / 2); }
        private static double cellUpper(int i) { return Math.min(1, RuleModel.gridPoint(i) + SPACING / 2); }

        private static int cellOf(double gf) {
            return (int) Math.max(0, Math.min(BINS - 1, Math.round((gf + 1) / SPACING)));
        }

        @Override
        public double density(double gf) {
            return gf >= -1 && gf <= 1 ? density[cellOf(gf)] : 0;
        }

        @Override
        public double integrate(double gfStart, double gfEnd) {
            double lower = Math.max(-1, Math.min(gfStart, gfEnd));
            double upper = Math.min(1, Math.max(gfStart, gfEnd));
            if (!(upper > lower)) return 0;
            double total = 0;
            for (int i = cellOf(lower); i <= cellOf(upper); i++) {
                double overlap = Math.min(upper, cellUpper(i)) - Math.max(lower, cellLower(i));
                if (overlap > 0) total += density[i] * overlap;
            }
            return total;
        }

        @Override
        public double findPeakGF() { return base.findPeakGF(); }

        @Override
        public double findValleyGF() { return base.findValleyGF(); }
    }
}
