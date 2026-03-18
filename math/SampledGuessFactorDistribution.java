package oog.mega.saguaro.math;

/**
 * A fast interval-query wrapper for GF distributions.
 *
 * It lazily caches exact probability mass over fixed GF slices, then answers
 * later integrate() calls by summing covered slices with proportional weighting
 * on the two boundary slices. This preserves the underlying exact distribution
 * object for learning and analysis while avoiding a large first-touch build.
 */
public final class SampledGuessFactorDistribution implements GuessFactorDistribution {
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final int DEFAULT_SEGMENTS = 256;

    private final GuessFactorDistribution base;
    private final double[] segmentMasses;
    private final boolean[] segmentComputed;
    private final double step;

    public SampledGuessFactorDistribution(GuessFactorDistribution base) {
        this(base, DEFAULT_SEGMENTS);
    }

    public SampledGuessFactorDistribution(GuessFactorDistribution base, int segments) {
        if (base == null) {
            throw new IllegalArgumentException("Base distribution must be non-null");
        }
        if (segments <= 0) {
            throw new IllegalArgumentException("Segment count must be positive: " + segments);
        }
        this.base = base;
        this.step = (MAX_GF - MIN_GF) / segments;
        this.segmentMasses = new double[segments];
        this.segmentComputed = new boolean[segments];
    }

    @Override
    public double density(double gf) {
        if (gf < MIN_GF || gf > MAX_GF) {
            return 0.0;
        }
        int segmentIndex = segmentIndexFor(gf);
        return segmentMass(segmentIndex) / step;
    }

    @Override
    public double integrate(double gfStart, double gfEnd) {
        double clampedStart = Math.max(MIN_GF, gfStart);
        double clampedEnd = Math.min(MAX_GF, gfEnd);
        if (clampedStart >= clampedEnd) {
            return 0.0;
        }

        int startIndex = segmentIndexFor(clampedStart);
        int endIndex = segmentIndexFor(Math.max(MIN_GF, clampedEnd - 1e-15));
        if (startIndex == endIndex) {
            return proportionalSegmentMass(startIndex, clampedStart, clampedEnd);
        }

        double mass = proportionalSegmentMass(
                startIndex, clampedStart, segmentEnd(startIndex));
        for (int i = startIndex + 1; i < endIndex; i++) {
            mass += segmentMass(i);
        }
        mass += proportionalSegmentMass(
                endIndex, segmentStart(endIndex), clampedEnd);
        return mass;
    }

    @Override
    public double findPeakGF() {
        int bestIndex = 0;
        double bestDensity = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < segmentMasses.length; i++) {
            double segmentDensity = segmentMass(i) / step;
            if (segmentDensity > bestDensity) {
                bestDensity = segmentDensity;
                bestIndex = i;
            }
        }
        return MIN_GF + (bestIndex + 0.5) * step;
    }

    @Override
    public double findValleyGF() {
        int bestIndex = 0;
        double bestDensity = Double.POSITIVE_INFINITY;
        for (int i = 0; i < segmentMasses.length; i++) {
            double segmentDensity = segmentMass(i) / step;
            if (segmentDensity < bestDensity) {
                bestDensity = segmentDensity;
                bestIndex = i;
            }
        }
        return MIN_GF + (bestIndex + 0.5) * step;
    }

    private int segmentIndexFor(double gf) {
        if (gf <= MIN_GF) {
            return 0;
        }
        if (gf >= MAX_GF) {
            return segmentMasses.length - 1;
        }
        int index = (int) ((gf - MIN_GF) / step);
        return Math.min(index, segmentMasses.length - 1);
    }

    private double segmentMass(int index) {
        if (!segmentComputed[index]) {
            segmentMasses[index] = base.integrate(segmentStart(index), segmentEnd(index));
            segmentComputed[index] = true;
        }
        return segmentMasses[index];
    }

    private double proportionalSegmentMass(int index, double visibleStart, double visibleEnd) {
        double span = visibleEnd - visibleStart;
        if (span <= 0.0) {
            return 0.0;
        }
        double fraction = span / step;
        if (fraction >= 1.0) {
            return segmentMass(index);
        }
        return segmentMass(index) * fraction;
    }

    private double segmentStart(int index) {
        return MIN_GF + index * step;
    }

    private double segmentEnd(int index) {
        return segmentStart(index) + step;
    }
}
