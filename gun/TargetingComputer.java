package oog.mega.saguaro.gun;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.math.DefaultDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.RobotHitbox;

/**
 * Computes the optimal firing angle for the single tracked opponent.
 */
public class TargetingComputer {

    private static final double GRADIENT_STEP = 0.001;
    private static final double CONVERGENCE_THRESHOLD = 1e-6;
    private static final int MAX_ITERATIONS = 100;

    /**
     * Represents a potential target with all the information needed for targeting.
     */
    public static class Target {
        public final double bearing;      // Absolute bearing to enemy
        public final double distance;     // Distance to enemy
        public final double mea;          // Maximum escape angle for this shot
        public final double damage;       // Damage if we hit (for weighting)
        public final GuessFactorDistribution gfDistribution;
        public final double circularGfWidth; // Cheap symmetric width used during peak search
        public final double gfStartOffset; // Target interval start in GF space
        public final double gfEndOffset;   // Target interval end in GF space
        public final double searchStartGf; // Precise clip start in naive GF space
        public final double searchEndGf;   // Precise clip end in naive GF space
        public final double searchMinAngle; // Peak-search lower bound including bot-width padding
        public final double searchMaxAngle; // Peak-search upper bound including bot-width padding

        public Target(double bearing, double distance, double bulletSpeed,
                      double damage,
                      GuessFactorDistribution gfDistribution,
                      double searchStartGf,
                      double searchEndGf) {
            this.bearing = bearing;
            this.distance = distance;
            this.mea = MathUtils.maxEscapeAngle(bulletSpeed);
            this.damage = damage;
            this.gfDistribution = gfDistribution;
            double safeDistance = Math.max(1e-9, distance);
            double ratio = Math.min(1.0, RobotHitbox.HALF_WIDTH / safeDistance);
            this.circularGfWidth = 2.0 * FastTrig.asin(ratio) / this.mea;
            double[] gfIntervalOffsets = RobotHitbox.guessFactorIntervalOffsets(bearing, distance, this.mea);
            this.gfStartOffset = gfIntervalOffsets[0];
            this.gfEndOffset = gfIntervalOffsets[1];
            this.searchStartGf = searchStartGf;
            this.searchEndGf = searchEndGf;
            double halfWidthAngle = (this.circularGfWidth * this.mea) * 0.5;
            this.searchMinAngle = this.bearing + this.searchStartGf * this.mea - halfWidthAngle;
            this.searchMaxAngle = this.bearing + this.searchEndGf * this.mea + halfWidthAngle;
        }
    }

    private Target target;

    public void addTarget(Target target) {
        if (this.target != null) {
            throw new IllegalStateException("TargetingComputer supports only one target in 1v1 mode.");
        }
        this.target = target;
    }

    public void clearTargets() {
        target = null;
    }

    /**
     * Computes the expected damage at a given absolute firing angle.
     * Hit probability times bullet damage for the single target.
     */
    public double expectedDamage(double angle) {
        if (target == null) {
            return 0.0;
        }
        double gf = MathUtils.angleToGf(target.bearing, angle, target.mea);
        double hitProb = target.gfDistribution.integrate(gf + target.gfStartOffset, gf + target.gfEndOffset);
        return target.damage * hitProb;
    }

    /**
     * Finds the optimal firing angle that maximizes expected damage.
     * Scans the derivative for sign changes to find all local maxima,
     * refines each with gradient ascent, and returns the best.
     *
     * @return the optimal absolute firing angle, or NaN if there is no target
     */
    public double findOptimalAngle() {
        if (target == null) {
            return Double.NaN;
        }

        List<Double> peaks = findAllPeakAngles();

        if (peaks.isEmpty()) {
            return target.bearing;
        }

        double bestAngle = Double.NaN;
        double bestDamage = Double.NEGATIVE_INFINITY;
        for (double angle : peaks) {
            double damage = expectedDamage(angle);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestAngle = angle;
            }
        }

        return bestAngle;
    }

    /**
     * Finds all local maxima of the expected damage function by scanning
     * for derivative sign changes (positive to negative).
     *
     * @return list of angles at local maxima, refined with gradient ascent
     */
    public List<Double> findAllPeakAngles() {
        List<Double> peaks = new ArrayList<>();

        if (target == null) {
            return peaks;
        }
        if (target.gfDistribution instanceof DefaultDistribution) {
            peaks.add(target.bearing);
            return peaks;
        }

        double minAngle = target.searchMinAngle;
        double maxAngle = target.searchMaxAngle;
        peaks.add(minAngle);
        if (maxAngle > minAngle + 1e-9) {
            peaks.add(maxAngle);
        }

        // Scan derivative for sign changes (+ to - indicates maximum)
        double searchSpan = Math.max(1e-6, maxAngle - minAngle);
        double step = Math.min(BotConfig.Gun.PEAK_SCAN_STEP, Math.max(1e-4, searchSpan / 8.0));
        double prevDeriv = approximateExpectedDamageDerivative(minAngle);

        for (double angle = minAngle + step; angle <= maxAngle; angle += step) {
            double currDeriv = approximateExpectedDamageDerivative(angle);

            // Sign change from positive to negative = local maximum
            if (prevDeriv > 0 && currDeriv <= 0) {
                // Refine with gradient ascent starting just before the sign change
                double refinedAngle = gradientAscent(angle - step / 2);
                peaks.add(refinedAngle);
            }

            prevDeriv = currDeriv;
        }

        return peaks;
    }

    /**
     * Performs gradient ascent to find a local maximum of expected damage.
     */
    private double gradientAscent(double startAngle) {
        double angle = clampToSearchWindow(startAngle);
        double damageAtAngle = approximateExpectedDamage(angle);

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double derivative = approximateExpectedDamageDerivative(angle);

            if (Math.abs(derivative) < CONVERGENCE_THRESHOLD) {
                break;
            }

            // Fixed step size in the direction of the derivative
            double step = GRADIENT_STEP * Math.signum(derivative);
            double newAngle = clampToSearchWindow(angle + step);

            // Only accept if it improves
            double newDamage = approximateExpectedDamage(newAngle);
            if (newDamage > damageAtAngle) {
                angle = newAngle;
                damageAtAngle = newDamage;
            } else {
                // Try smaller step
                step *= 0.5;
                newAngle = clampToSearchWindow(angle + step);
                newDamage = approximateExpectedDamage(newAngle);
                if (newDamage > damageAtAngle) {
                    angle = newAngle;
                    damageAtAngle = newDamage;
                } else {
                    break;  // Converged
                }
            }
        }

        return angle;
    }

    /**
     * Returns 1 if a target is loaded, otherwise 0.
     */
    public int getTargetCount() {
        return target == null ? 0 : 1;
    }

    private double approximateExpectedDamage(double angle) {
        double gf = MathUtils.angleToGf(target.bearing, angle, target.mea);
        double halfWidth = target.circularGfWidth * 0.5;
        double hitProb = target.gfDistribution.integrate(gf - halfWidth, gf + halfWidth);
        return target.damage * hitProb;
    }

    private double approximateExpectedDamageDerivative(double angle) {
        double gf = MathUtils.angleToGf(target.bearing, angle, target.mea);
        double halfWidth = target.circularGfWidth * 0.5;
        double densityHigh = target.gfDistribution.density(gf + halfWidth);
        double densityLow = target.gfDistribution.density(gf - halfWidth);
        return target.damage * (densityHigh - densityLow) / target.mea;
    }

    private double clampToSearchWindow(double angle) {
        return Math.max(target.searchMinAngle, Math.min(target.searchMaxAngle, angle));
    }
}
