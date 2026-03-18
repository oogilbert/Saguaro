package oog.mega.saguaro.math;

public final class IntervalKDEDistribution implements GuessFactorDistribution {
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final int SCAN_STEPS = 200;
    private static final double MIN_INTERVAL_WIDTH = 1e-9;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);

    private final double[] lowerBounds;
    private final double[] upperBounds;
    private final double[] weights;
    private final double bandwidth;
    private final double normalization;

    public IntervalKDEDistribution(double[] lowerBounds,
                                   double[] upperBounds,
                                   double[] weights,
                                   double bandwidth) {
        if (lowerBounds == null || upperBounds == null || lowerBounds.length == 0) {
            throw new IllegalArgumentException("IntervalKDEDistribution requires at least one interval");
        }
        if (lowerBounds.length != upperBounds.length) {
            throw new IllegalArgumentException("IntervalKDEDistribution interval arrays must match");
        }
        if (weights != null && weights.length != lowerBounds.length) {
            throw new IllegalArgumentException("IntervalKDEDistribution weights must match interval count");
        }
        this.lowerBounds = lowerBounds;
        this.upperBounds = upperBounds;
        this.weights = weights != null ? weights : uniformWeights(lowerBounds.length);
        this.bandwidth = bandwidth;

        double totalMass = 0.0;
        for (int i = 0; i < lowerBounds.length; i++) {
            validateInterval(lowerBounds[i], upperBounds[i]);
            totalMass += this.weights[i] * kernelIntegral(MIN_GF, MAX_GF, lowerBounds[i], upperBounds[i], bandwidth);
        }
        this.normalization = totalMass > 0.0 ? totalMass : 1.0;
    }

    private static double[] uniformWeights(int count) {
        double[] weights = new double[count];
        for (int i = 0; i < count; i++) {
            weights[i] = 1.0;
        }
        return weights;
    }

    @Override
    public double density(double gf) {
        if (gf < MIN_GF || gf > MAX_GF) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < lowerBounds.length; i++) {
            sum += weights[i] * kernelDensity(gf, lowerBounds[i], upperBounds[i], bandwidth);
        }
        return sum / normalization;
    }

    @Override
    public double integrate(double gfStart, double gfEnd) {
        double clampedStart = Math.max(MIN_GF, gfStart);
        double clampedEnd = Math.min(MAX_GF, gfEnd);
        if (clampedStart >= clampedEnd) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < lowerBounds.length; i++) {
            sum += weights[i] * kernelIntegral(
                    clampedStart, clampedEnd, lowerBounds[i], upperBounds[i], bandwidth);
        }
        return sum / normalization;
    }

    @Override
    public double findPeakGF() {
        double bestGf = 0.0;
        double bestDensity = Double.NEGATIVE_INFINITY;
        for (int i = 0; i <= SCAN_STEPS; i++) {
            double gf = MIN_GF + (MAX_GF - MIN_GF) * i / SCAN_STEPS;
            double density = density(gf);
            if (density > bestDensity) {
                bestDensity = density;
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
            double density = density(gf);
            if (density < bestDensity) {
                bestDensity = density;
                bestGf = gf;
            }
        }
        return bestGf;
    }

    private static double kernelDensity(double gf,
                                        double lowerBound,
                                        double upperBound,
                                        double bandwidth) {
        double intervalWidth = upperBound - lowerBound;
        if (intervalWidth <= MIN_INTERVAL_WIDTH) {
            return MathUtils.gaussian(gf, lowerBound, bandwidth);
        }
        double lowerCdf = MathUtils.normalCdf((lowerBound - gf) / bandwidth);
        double upperCdf = MathUtils.normalCdf((upperBound - gf) / bandwidth);
        return (upperCdf - lowerCdf) / intervalWidth;
    }

    private static double kernelIntegral(double gfStart,
                                         double gfEnd,
                                         double lowerBound,
                                         double upperBound,
                                         double bandwidth) {
        double intervalWidth = upperBound - lowerBound;
        if (intervalWidth <= MIN_INTERVAL_WIDTH) {
            return MathUtils.gaussianIntegral(gfStart, gfEnd, lowerBound, bandwidth);
        }
        double endMass = normalCdfPrimitive(gfEnd, upperBound, bandwidth)
                - normalCdfPrimitive(gfEnd, lowerBound, bandwidth);
        double startMass = normalCdfPrimitive(gfStart, upperBound, bandwidth)
                - normalCdfPrimitive(gfStart, lowerBound, bandwidth);
        return (endMass - startMass) / intervalWidth;
    }

    private static double normalCdfPrimitive(double x, double mean, double bandwidth) {
        double delta = x - mean;
        double z = delta / bandwidth;
        double cdf = MathUtils.normalCdf(z);
        double standardPdf = Math.exp(-0.5 * z * z) / SQRT_2PI;
        return -delta * cdf - bandwidth * standardPdf;
    }

    private static void validateInterval(double lowerBound, double upperBound) {
        if (!Double.isFinite(lowerBound) || !Double.isFinite(upperBound) || lowerBound > upperBound) {
            throw new IllegalArgumentException("Interval KDE requires ordered finite intervals");
        }
    }
}
