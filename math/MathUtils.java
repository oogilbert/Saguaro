package oog.mega.saguaro.math;

public final class MathUtils {

    private static final double SQRT_2PI = Math.sqrt(2 * Math.PI);
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double INV_SQRT_2 = 1.0 / Math.sqrt(2.0);
    private static final double MAX_BOT_SPEED = 8.0;
    private static final double MIN_PRECISE_ESCAPE_ANGLE = 1e-3;
    private static final double ERF_A1 = 0.254829592;
    private static final double ERF_A2 = -0.284496736;
    private static final double ERF_A3 = 1.421413741;
    private static final double ERF_A4 = -1.453152027;
    private static final double ERF_A5 = 1.061405429;
    private static final double ERF_P = 0.3275911;

    /**
     * Computes the Gaussian (normal) probability density function.
     * @param x the point to evaluate
     * @param mean the mean of the distribution
     * @param stdDev the standard deviation
     * @return the probability density at x
     */
    public static double gaussian(double x, double mean, double stdDev) {
        double z = (x - mean) / stdDev;
        return Math.exp(-0.5 * z * z) / (stdDev * SQRT_2PI);
    }

    /**
     * Computes the cumulative distribution function (CDF) of the standard normal distribution.
     * Uses an approximation accurate to about 1e-7.
     * @param x the point to evaluate
     * @return P(Z <= x) where Z is standard normal
     */
    public static double normalCdf(double x) {
        // Approximation using error function identity: Phi(x) = 0.5 * (1 + erf(x / sqrt(2)))
        return 0.5 * (1 + erf(x * INV_SQRT_2));
    }

    /**
     * Approximation of the error function.
     * Abramowitz and Stegun approximation 7.1.26, accurate to 1.5e-7.
     */
    public static double erf(double x) {
        double sign = x < 0 ? -1 : 1;
        x = Math.abs(x);

        double t = 1.0 / (1.0 + ERF_P * x);
        double y = 1.0 - (((((ERF_A5 * t + ERF_A4) * t) + ERF_A3) * t + ERF_A2) * t + ERF_A1)
                * t * Math.exp(-x * x);
        return sign * y;
    }

    /**
     * Integrates a Gaussian from a to b.
     * @param a lower bound
     * @param b upper bound
     * @param mean the mean of the Gaussian
     * @param stdDev the standard deviation
     * @return the integral of the Gaussian from a to b
     */
    public static double gaussianIntegral(double a, double b, double mean, double stdDev) {
        double za = (a - mean) / stdDev;
        double zb = (b - mean) / stdDev;
        return normalCdf(zb) - normalCdf(za);
    }

    /**
     * Computes the Maximum Escape Angle (MEA).
     * This is the maximum angle a bot can reach before a bullet arrives.
     *
     * @param bulletSpeed speed of the bullet (20 - 3*firepower)
     * @return the maximum escape angle in radians
     */
    public static double maxEscapeAngle(double bulletSpeed) {
        // MEA = asin(maxBotSpeed / bulletSpeed)
        // Max bot speed is 8.0
        double ratio = MAX_BOT_SPEED / bulletSpeed;
        if (ratio >= 1.0) {
            return Math.PI / 2;
        }
        return FastTrig.asin(ratio);
    }

    /**
     * Computes asymmetric in-field escape angles using the same non-iterative
     * escape-circle geometry BeepBoop uses.
     *
     * @return {negativeSideMagnitude, positiveSideMagnitude} in radians
     */
    public static double[] inFieldMaxEscapeAngles(double sourceX,
                                                  double sourceY,
                                                  double targetX,
                                                  double targetY,
                                                  double bulletSpeed,
                                                  double battlefieldWidth,
                                                  double battlefieldHeight) {
        double margin = RobotHitbox.HALF_WIDTH;
        double minX = margin;
        double maxX = battlefieldWidth - margin;
        double minY = margin;
        double maxY = battlefieldHeight - margin;
        double absoluteBearing = Math.atan2(targetX - sourceX, targetY - sourceY);
        double[] escapeCircle = escapeCircle(sourceX, sourceY, targetX, targetY, absoluteBearing, bulletSpeed);
        double[] offsetExtrema = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};

        addTangentOffsets(
                offsetExtrema,
                sourceX, sourceY,
                absoluteBearing,
                escapeCircle[0], escapeCircle[1], escapeCircle[2],
                minX, maxX, minY, maxY);
        addCircleRectangleOffsets(
                offsetExtrema,
                sourceX, sourceY,
                absoluteBearing,
                escapeCircle[0], escapeCircle[1], escapeCircle[2],
                minX, maxX, minY, maxY);

        double negativeMagnitude = Math.max(
                Math.abs(Math.min(0.0, offsetExtrema[0])),
                MIN_PRECISE_ESCAPE_ANGLE);
        double positiveMagnitude = Math.max(
                Math.max(0.0, offsetExtrema[1]),
                MIN_PRECISE_ESCAPE_ANGLE);
        return new double[]{negativeMagnitude, positiveMagnitude};
    }

    /**
     * Converts the asymmetric in-field escape-angle bound into the bot's
     * existing naive-GF coordinate system.
     *
     * @return {minGF, maxGF} in the naive [-1, 1] MEA space
     */
    public static double[] inFieldMaxEscapeGfRange(double sourceX,
                                                   double sourceY,
                                                   double targetX,
                                                   double targetY,
                                                   double bulletSpeed,
                                                   double battlefieldWidth,
                                                   double battlefieldHeight) {
        double mea = maxEscapeAngle(bulletSpeed);
        double[] escapeAngles = inFieldMaxEscapeAngles(
                sourceX, sourceY, targetX, targetY, bulletSpeed, battlefieldWidth, battlefieldHeight);
        double minGf = Math.max(-1.0, Math.min(1.0, -escapeAngles[0] / mea));
        double maxGf = Math.max(-1.0, Math.min(1.0, escapeAngles[1] / mea));
        if (minGf > maxGf) {
            return new double[]{maxGf, minGf};
        }
        return new double[]{minGf, maxGf};
    }

    /**
     * Converts a guess factor to an absolute firing angle.
     *
     * @param bearing absolute bearing to the enemy
     * @param gf guess factor in [-1, 1]
     * @param mea maximum escape angle
     * @return absolute firing angle in radians
     */
    public static double gfToAngle(double bearing, double gf, double mea) {
        return bearing + gf * mea;
    }

    /**
     * Converts an absolute firing angle to a guess factor.
     *
     * @param bearing absolute bearing to the enemy
     * @param angle absolute firing angle
     * @param mea maximum escape angle
     * @return guess factor (may be outside [-1, 1] if angle is beyond MEA)
     */
    public static double angleToGf(double bearing, double angle, double mea) {
        return normalizeAngle(angle - bearing) / mea;
    }

    /**
     * Normalizes an angle to the range [-PI, PI].
     */
    public static double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= TWO_PI;
        while (angle < -Math.PI) angle += TWO_PI;
        return angle;
    }

    private static double[] escapeCircle(double sourceX,
                                         double sourceY,
                                         double targetX,
                                         double targetY,
                                         double absoluteBearing,
                                         double bulletSpeed) {
        double distance = Math.hypot(targetX - sourceX, targetY - sourceY);
        double r1 = MAX_BOT_SPEED * distance / (bulletSpeed + MAX_BOT_SPEED);
        double r2 = MAX_BOT_SPEED * distance / (bulletSpeed - MAX_BOT_SPEED);
        double radius = (r1 + r2) * 0.5;
        double centerOffset = radius - r1;
        double centerX = targetX + FastTrig.sin(absoluteBearing) * centerOffset;
        double centerY = targetY + FastTrig.cos(absoluteBearing) * centerOffset;
        return new double[]{centerX, centerY, radius};
    }

    private static void addTangentOffsets(double[] offsetExtrema,
                                          double sourceX,
                                          double sourceY,
                                          double referenceBearing,
                                          double circleX,
                                          double circleY,
                                          double circleRadius,
                                          double minX,
                                          double maxX,
                                          double minY,
                                          double maxY) {
        double distance = Math.hypot(circleX - sourceX, circleY - sourceY);
        if (distance < circleRadius || distance < 1e-9) {
            return;
        }
        double bearingToCenter = Math.atan2(circleX - sourceX, circleY - sourceY);
        double tangentAngle = FastTrig.asin(Math.min(1.0, circleRadius / distance));
        double tangentLengthSq = distance * distance - circleRadius * circleRadius;
        double tangentLength = Math.sqrt(Math.max(0.0, tangentLengthSq));

        addProjectedOffsetIfInBounds(
                offsetExtrema, sourceX, sourceY, referenceBearing,
                bearingToCenter - tangentAngle, tangentLength,
                minX, maxX, minY, maxY);
        addProjectedOffsetIfInBounds(
                offsetExtrema, sourceX, sourceY, referenceBearing,
                bearingToCenter + tangentAngle, tangentLength,
                minX, maxX, minY, maxY);
    }

    private static void addCircleRectangleOffsets(double[] offsetExtrema,
                                                  double sourceX,
                                                  double sourceY,
                                                  double referenceBearing,
                                                  double circleX,
                                                  double circleY,
                                                  double circleRadius,
                                                  double minX,
                                                  double maxX,
                                                  double minY,
                                                  double maxY) {
        double radiusSq = circleRadius * circleRadius;
        addVerticalCircleOffsets(offsetExtrema, sourceX, sourceY, referenceBearing, circleX, circleY, radiusSq, minX, minY, maxY);
        addVerticalCircleOffsets(offsetExtrema, sourceX, sourceY, referenceBearing, circleX, circleY, radiusSq, maxX, minY, maxY);
        addHorizontalCircleOffsets(offsetExtrema, sourceX, sourceY, referenceBearing, circleX, circleY, radiusSq, minY, minX, maxX);
        addHorizontalCircleOffsets(offsetExtrema, sourceX, sourceY, referenceBearing, circleX, circleY, radiusSq, maxY, minX, maxX);
    }

    private static void addVerticalCircleOffsets(double[] offsetExtrema,
                                                 double sourceX,
                                                 double sourceY,
                                                 double referenceBearing,
                                                 double circleX,
                                                 double circleY,
                                                 double radiusSq,
                                                 double edgeX,
                                                 double minY,
                                                 double maxY) {
        double deltaX = edgeX - circleX;
        double rootSq = radiusSq - deltaX * deltaX;
        if (rootSq < 0.0) {
            return;
        }
        double root = Math.sqrt(Math.max(0.0, rootSq));
        addOffsetIfInBounds(offsetExtrema, sourceX, sourceY, referenceBearing, edgeX, circleY + root, edgeX, edgeX, minY, maxY);
        if (root > 0.0) {
            addOffsetIfInBounds(offsetExtrema, sourceX, sourceY, referenceBearing, edgeX, circleY - root, edgeX, edgeX, minY, maxY);
        }
    }

    private static void addHorizontalCircleOffsets(double[] offsetExtrema,
                                                   double sourceX,
                                                   double sourceY,
                                                   double referenceBearing,
                                                   double circleX,
                                                   double circleY,
                                                   double radiusSq,
                                                   double edgeY,
                                                   double minX,
                                                   double maxX) {
        double deltaY = edgeY - circleY;
        double rootSq = radiusSq - deltaY * deltaY;
        if (rootSq < 0.0) {
            return;
        }
        double root = Math.sqrt(Math.max(0.0, rootSq));
        addOffsetIfInBounds(offsetExtrema, sourceX, sourceY, referenceBearing, circleX + root, edgeY, minX, maxX, edgeY, edgeY);
        if (root > 0.0) {
            addOffsetIfInBounds(offsetExtrema, sourceX, sourceY, referenceBearing, circleX - root, edgeY, minX, maxX, edgeY, edgeY);
        }
    }

    private static void addProjectedOffsetIfInBounds(double[] offsetExtrema,
                                                     double sourceX,
                                                     double sourceY,
                                                     double referenceBearing,
                                                     double angle,
                                                     double distance,
                                                     double minX,
                                                     double maxX,
                                                     double minY,
                                                     double maxY) {
        double pointX = sourceX + FastTrig.sin(angle) * distance;
        double pointY = sourceY + FastTrig.cos(angle) * distance;
        addOffsetIfInBounds(offsetExtrema, sourceX, sourceY, referenceBearing, pointX, pointY, minX, maxX, minY, maxY);
    }

    private static void addOffsetIfInBounds(double[] offsetExtrema,
                                            double sourceX,
                                            double sourceY,
                                            double referenceBearing,
                                            double pointX,
                                            double pointY,
                                            double minX,
                                            double maxX,
                                            double minY,
                                            double maxY) {
        if (pointX < minX || pointX > maxX || pointY < minY || pointY > maxY) {
            return;
        }
        double pointBearing = Math.atan2(pointX - sourceX, pointY - sourceY);
        double offset = normalizeAngle(pointBearing - referenceBearing);
        offsetExtrema[0] = Math.min(offsetExtrema[0], offset);
        offsetExtrema[1] = Math.max(offsetExtrema[1], offset);
    }
}
