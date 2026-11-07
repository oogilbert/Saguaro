package oog.mega.saguaro.info.learning.rule;

import oog.mega.saguaro.math.GuessFactorDistribution;

/**
 * The rule model's prediction for one wave, in the canonical frame, as Saguaro consumes
 * it: a hit-chance density, piecewise constant over the model's grid cells.
 *
 * The model's raw output is a probability per cell. What Saguaro integrates over a body
 * interval has to be the chance of a hit there, and the raw mass is not that when the
 * opponent learns from our choices -- so the density is the raw density passed through
 * the realised-outcome map at the scale of one body width: integrating it across a body
 * gives the calibrated hit chance, and it stays additive when shadows split the body
 * into pieces. Unlike the raw masses it does not sum to one, and is not meant to.
 */
public final class RuleDistribution implements GuessFactorDistribution {
    private static final int BINS = RuleModel.BINS;
    private static final double SPACING = 2.0 / (BINS - 1);

    private final double[] raw = new double[BINS];
    private final double[] calibrated = new double[BINS];

    /**
     * @param masses     raw probability of each grid cell; zero outside the support
     * @param bodyWidth  the target's body width in guess-factor units
     * @param map        the realised-outcome map, or null to expose the raw density
     */
    public RuleDistribution(double[] masses, double bodyWidth, HitCalibration map) {
        this(masses, bodyWidth, map, Double.NaN);
    }

    /**
     * @param uniform the hit chance a uniform guess over the target's physical reach has
     *                on this wave, which the map is centred on; NaN for the fixed centre
     */
    public RuleDistribution(double[] masses, double bodyWidth, HitCalibration map, double uniform) {
        double width = Math.max(1e-3, bodyWidth);
        for (int i = 0; i < BINS; i++) {
            raw[i] = masses[i] / cellWidth(i);
            calibrated[i] = map == null || raw[i] <= 0
                    ? raw[i]
                    : map.calibrate(Math.min(0.999, raw[i] * width), uniform) / width;
        }
    }

    /** The hit chance of a uniform guess over a reach, for a body of a width. */
    public static double uniformChance(double bodyWidth, double reachLower, double reachUpper) {
        double reach = reachUpper - reachLower;
        return reach > bodyWidth ? bodyWidth / reach : 0.999;
    }

    private static double cellLower(int i) { return Math.max(-1, RuleModel.gridPoint(i) - SPACING / 2); }
    private static double cellUpper(int i) { return Math.min(1, RuleModel.gridPoint(i) + SPACING / 2); }
    private static double cellWidth(int i) { return cellUpper(i) - cellLower(i); }

    private static int cellOf(double gf) {
        return (int) Math.max(0, Math.min(BINS - 1, Math.round((gf + 1) / SPACING)));
    }

    @Override
    public double density(double gf) {
        if (!(gf >= -1 && gf <= 1)) return 0;
        return calibrated[cellOf(gf)];
    }

    @Override
    public double integrate(double gfStart, double gfEnd) {
        return integrate(calibrated, gfStart, gfEnd);
    }

    /** Raw probability mass over an interval, before the realised-outcome map. */
    public double rawIntegrate(double gfStart, double gfEnd) {
        return integrate(raw, gfStart, gfEnd);
    }

    private static double integrate(double[] density, double gfStart, double gfEnd) {
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
    public double findPeakGF() {
        int best = 0;
        for (int i = 1; i < BINS; i++) if (calibrated[i] > calibrated[best]) best = i;
        return RuleModel.gridPoint(best);
    }

    @Override
    public double findValleyGF() {
        int best = -1;
        for (int i = 0; i < BINS; i++) {
            if (raw[i] <= 0) continue;
            if (best < 0 || calibrated[i] < calibrated[best]) best = i;
        }
        return RuleModel.gridPoint(best < 0 ? BINS / 2 : best);
    }
}
