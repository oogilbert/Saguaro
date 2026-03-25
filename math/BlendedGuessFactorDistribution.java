package oog.mega.saguaro.math;

public class BlendedGuessFactorDistribution implements GuessFactorDistribution {
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final int SCAN_STEPS = 200;

    private final GuessFactorDistribution d1;
    private final GuessFactorDistribution d2;
    private final double w1;
    private final double w2;
    private final double totalWeight;

    public BlendedGuessFactorDistribution(GuessFactorDistribution d1, double w1,
                                          GuessFactorDistribution d2, double w2) {
        if (d1 == null || d2 == null) {
            throw new IllegalArgumentException("BlendedGuessFactorDistribution requires non-null distributions");
        }
        if (!(w1 >= 0.0) || !(w2 >= 0.0)) {
            throw new IllegalArgumentException("BlendedGuessFactorDistribution requires non-negative weights");
        }
        this.d1 = d1;
        this.d2 = d2;
        this.w1 = w1;
        this.w2 = w2;
        this.totalWeight = w1 + w2;
    }

    @Override
    public double density(double gf) {
        if (!(totalWeight > 0.0)) {
            return 0.0;
        }
        return (w1 * d1.density(gf) + w2 * d2.density(gf)) / totalWeight;
    }

    @Override
    public double integrate(double gfStart, double gfEnd) {
        if (!(totalWeight > 0.0)) {
            return 0.0;
        }
        return (w1 * d1.integrate(gfStart, gfEnd) + w2 * d2.integrate(gfStart, gfEnd)) / totalWeight;
    }

    @Override
    public double findPeakGF() {
        double bestGf = 0.0;
        double bestDensity = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= SCAN_STEPS; i++) {
            double gf = MIN_GF + (MAX_GF - MIN_GF) * i / SCAN_STEPS;
            double d = density(gf);
            if (d > bestDensity) {
                bestDensity = d;
                bestGf = gf;
            }
        }
        return bestGf;
    }

    @Override
    public double findValleyGF() {
        double bestGf = 0.0;
        double bestDensity = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= SCAN_STEPS; i++) {
            double gf = MIN_GF + (MAX_GF - MIN_GF) * i / SCAN_STEPS;
            double d = density(gf);
            if (d < bestDensity) {
                bestDensity = d;
                bestGf = gf;
            }
        }
        return bestGf;
    }
}
