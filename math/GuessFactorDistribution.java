package oog.mega.saguaro.math;

/**
 * Represents a probability distribution over guess factors (GF).
 * GF ranges from -1 (max escape angle in one direction) to +1 (max in other direction),
 * with 0 being head-on.
 */
public interface GuessFactorDistribution {

    /**
     * Returns the probability density at the given guess factor.
     * @param gf guess factor in range [-1, 1]
     * @return probability density (not necessarily normalized)
     */
    double density(double gf);

    /**
     * Integrates the density over a range of guess factors.
     * @param gfStart start of integration range
     * @param gfEnd end of integration range
     * @return integrated probability over the range
     */
    double integrate(double gfStart, double gfEnd);

    /**
     * Finds the guess factor with the highest probability density.
     * @return GF value at the peak
     */
    double findPeakGF();

    /**
     * Finds the guess factor with the lowest probability density.
     * @return GF value at the valley
     */
    double findValleyGF();
}
