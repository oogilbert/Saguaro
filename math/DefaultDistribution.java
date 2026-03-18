package oog.mega.saguaro.math;

import oog.mega.saguaro.info.wave.Wave;

/**
 * Default guess-factor prior used when no learned data is available.
 *
 * Implemented as an equal-weight mixture of two truncated Gaussians:
 * a head-on kernel and a simple linear-targeting kernel.
 */
public class DefaultDistribution implements GuessFactorDistribution {

    private static final double DEFAULT_BANDWIDTH = 0.2;
    private static final double MIN_GF = -1.0;
    private static final double MAX_GF = 1.0;
    private static final double HALF_MASS = 0.5;

    private final double bandwidth;
    private final double headOnMean;
    private final double linearMean;
    private final double headOnInvNormalization;
    private final double linearInvNormalization;

    public DefaultDistribution() {
        this(0.0, 0.0, DEFAULT_BANDWIDTH);
    }

    public DefaultDistribution(double bandwidth) {
        this(0.0, 0.0, bandwidth);
    }

    public DefaultDistribution(double headOnMean, double linearMean, double bandwidth) {
        if (!Double.isFinite(bandwidth) || bandwidth <= 0.0) {
            throw new IllegalArgumentException("Bandwidth must be positive and finite: " + bandwidth);
        }
        if (!Double.isFinite(headOnMean) || !Double.isFinite(linearMean)) {
            throw new IllegalArgumentException("Default-distribution means must be finite");
        }
        this.bandwidth = bandwidth;
        this.headOnMean = projectToDomain(headOnMean);
        this.linearMean = projectToDomain(linearMean);
        this.headOnInvNormalization = inverseNormalization(this.headOnMean, bandwidth);
        this.linearInvNormalization = inverseNormalization(this.linearMean, bandwidth);
    }

    public static DefaultDistribution forTargetingState(double sourceX,
                                                        double sourceY,
                                                        double targetX,
                                                        double targetY,
                                                        double targetHeading,
                                                        double targetVelocity,
                                                        double bulletSpeed) {
        return forTargetingState(
                sourceX,
                sourceY,
                targetX,
                targetY,
                targetHeading,
                targetVelocity,
                bulletSpeed,
                DEFAULT_BANDWIDTH);
    }

    public static DefaultDistribution forTargetingState(double sourceX,
                                                        double sourceY,
                                                        double targetX,
                                                        double targetY,
                                                        double targetHeading,
                                                        double targetVelocity,
                                                        double bulletSpeed,
                                                        double bandwidth) {
        double absoluteBearing = Math.atan2(targetX - sourceX, targetY - sourceY);
        double linearAngle = simpleLinearTargetingAngle(
                absoluteBearing,
                targetHeading,
                targetVelocity,
                bulletSpeed);
        double mea = MathUtils.maxEscapeAngle(bulletSpeed);
        double linearGf = MathUtils.angleToGf(absoluteBearing, linearAngle, mea);
        return new DefaultDistribution(0.0, linearGf, bandwidth);
    }

    public static DefaultDistribution forObservedEnemyWave(Wave wave) {
        return forObservedEnemyWave(wave, DEFAULT_BANDWIDTH);
    }

    public static DefaultDistribution forObservedEnemyWave(Wave wave, double bandwidth) {
        if (wave == null) {
            throw new IllegalArgumentException("Observed-wave default distribution requires a non-null wave");
        }
        if (Double.isFinite(wave.priorTickShooterX)
                && Double.isFinite(wave.priorTickShooterY)
                && Double.isFinite(wave.priorTickTargetX)
                && Double.isFinite(wave.priorTickTargetY)
                && Double.isFinite(wave.priorTickTargetHeading)
                && Double.isFinite(wave.priorTickTargetVelocity)
                && Double.isFinite(wave.targetX)
                && Double.isFinite(wave.targetY)) {
            double referenceBearing = Math.atan2(wave.targetX - wave.originX, wave.targetY - wave.originY);
            double headOnAngle = Math.atan2(
                    wave.priorTickTargetX - wave.priorTickShooterX,
                    wave.priorTickTargetY - wave.priorTickShooterY);
            double linearAngle = simpleLinearTargetingAngle(
                    headOnAngle,
                    wave.priorTickTargetHeading,
                    wave.priorTickTargetVelocity,
                    wave.speed);
            double mea = MathUtils.maxEscapeAngle(wave.speed);
            double headOnGf = MathUtils.angleToGf(referenceBearing, headOnAngle, mea);
            double linearGf = MathUtils.angleToGf(referenceBearing, linearAngle, mea);
            return new DefaultDistribution(headOnGf, linearGf, bandwidth);
        }
        if (Double.isFinite(wave.targetX)
                && Double.isFinite(wave.targetY)
                && Double.isFinite(wave.fireTimeTargetHeading)
                && Double.isFinite(wave.fireTimeTargetVelocity)) {
            return forTargetingState(
                    wave.originX,
                    wave.originY,
                    wave.targetX,
                    wave.targetY,
                    wave.fireTimeTargetHeading,
                    wave.fireTimeTargetVelocity,
                    wave.speed,
                    bandwidth);
        }
        return new DefaultDistribution(bandwidth);
    }

    @Override
    public double density(double gf) {
        if (gf < MIN_GF || gf > MAX_GF) {
            return 0.0;
        }
        return HALF_MASS * gaussianDensity(gf, headOnMean, headOnInvNormalization)
                + HALF_MASS * gaussianDensity(gf, linearMean, linearInvNormalization);
    }

    @Override
    public double integrate(double gfStart, double gfEnd) {
        double clampedStart = Math.max(MIN_GF, gfStart);
        double clampedEnd = Math.min(MAX_GF, gfEnd);
        if (clampedStart >= clampedEnd) {
            return 0.0;
        }
        return HALF_MASS * gaussianIntegral(clampedStart, clampedEnd, headOnMean, headOnInvNormalization)
                + HALF_MASS * gaussianIntegral(clampedStart, clampedEnd, linearMean, linearInvNormalization);
    }

    @Override
    public double findPeakGF() {
        double bestGf = projectToDomain(headOnMean);
        double bestDensity = density(bestGf);
        double linearGf = projectToDomain(linearMean);
        double linearDensity = density(linearGf);
        if (linearDensity > bestDensity) {
            bestGf = linearGf;
            bestDensity = linearDensity;
        }
        double midpoint = projectToDomain((headOnMean + linearMean) * 0.5);
        double midpointDensity = density(midpoint);
        if (midpointDensity > bestDensity) {
            bestGf = midpoint;
        }
        double leftDensity = density(MIN_GF);
        if (leftDensity > bestDensity) {
            bestGf = MIN_GF;
            bestDensity = leftDensity;
        }
        double rightDensity = density(MAX_GF);
        if (rightDensity > bestDensity) {
            bestGf = MAX_GF;
        }
        return bestGf;
    }

    @Override
    public double findValleyGF() {
        double bestGf = MIN_GF;
        double bestDensity = density(bestGf);
        double rightDensity = density(MAX_GF);
        if (rightDensity < bestDensity) {
            bestGf = MAX_GF;
            bestDensity = rightDensity;
        }
        double midpoint = projectToDomain((headOnMean + linearMean) * 0.5);
        double midpointDensity = density(midpoint);
        if (midpointDensity < bestDensity) {
            bestGf = midpoint;
        }
        return bestGf;
    }

    private static double simpleLinearTargetingAngle(double headOnAngle,
                                                     double targetHeading,
                                                     double targetVelocity,
                                                     double bulletSpeed) {
        double lateralVelocity = targetVelocity * Math.sin(targetHeading - headOnAngle);
        double offset = lateralVelocity / bulletSpeed;
        return MathUtils.normalizeAngle(headOnAngle + offset);
    }

    private static double projectToDomain(double gf) {
        return Math.max(MIN_GF, Math.min(MAX_GF, gf));
    }

    private static double inverseNormalization(double mean, double bandwidth) {
        double normalization = MathUtils.gaussianIntegral(MIN_GF, MAX_GF, mean, bandwidth);
        if (!Double.isFinite(normalization) || normalization <= 0.0) {
            throw new IllegalStateException("Invalid normalization for bandwidth: " + bandwidth);
        }
        return 1.0 / normalization;
    }

    private double gaussianDensity(double gf, double mean, double invNormalization) {
        return MathUtils.gaussian(gf, mean, bandwidth) * invNormalization;
    }

    private double gaussianIntegral(double start, double end, double mean, double invNormalization) {
        return MathUtils.gaussianIntegral(start, end, mean, bandwidth) * invNormalization;
    }
}
