package oog.mega.saguaro.math;

public class KDEDistribution implements GuessFactorDistribution {
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final int SCAN_STEPS = 200;

    private final double[] centers;
    private final double[] weights;
    private final double bandwidth;
    private final double normalization;

    public KDEDistribution(double[] centers, double bandwidth) {
        this(centers, null, bandwidth);
    }

    public KDEDistribution(double[] centers, double[] weights, double bandwidth) {
        if (centers == null || centers.length == 0) {
            throw new IllegalArgumentException("KDEDistribution requires at least one center");
        }
        if (weights != null && weights.length != centers.length) {
            throw new IllegalArgumentException("KDEDistribution weights must match center count");
        }
        this.centers = centers;
        this.weights = weights != null ? weights : uniformWeights(centers.length);
        this.bandwidth = bandwidth;

        // Compute normalization: sum of each kernel's mass within [-1, 1]
        double totalMass = 0.0;
        for (int i = 0; i < centers.length; i++) {
            totalMass += this.weights[i] * MathUtils.gaussianIntegral(MIN_GF, MAX_GF, centers[i], bandwidth);
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
        for (int i = 0; i < centers.length; i++) {
            sum += weights[i] * MathUtils.gaussian(gf, centers[i], bandwidth);
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
        for (int i = 0; i < centers.length; i++) {
            sum += weights[i] * MathUtils.gaussianIntegral(clampedStart, clampedEnd, centers[i], bandwidth);
        }
        return sum / normalization;
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
