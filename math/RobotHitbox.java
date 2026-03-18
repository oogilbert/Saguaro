package oog.mega.saguaro.math;

import java.util.Arrays;

/**
 * Geometry helpers for Robocode's non-rotating 36x36 robot hitbox.
 */
public final class RobotHitbox {
    public static final double HALF_WIDTH = 18.0;
    private static final double FULL_CIRCLE = Math.PI * 2.0;
    private static final int MAX_INTERSECTION_ANGLE_COUNT = 20;

    private RobotHitbox() {
    }

    public static double minDistance(double sourceX, double sourceY, double centerX, double centerY) {
        double dx = Math.max(0.0, Math.abs(centerX - sourceX) - HALF_WIDTH);
        double dy = Math.max(0.0, Math.abs(centerY - sourceY) - HALF_WIDTH);
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static double maxDistance(double sourceX, double sourceY, double centerX, double centerY) {
        double dx = Math.abs(centerX - sourceX) + HALF_WIDTH;
        double dy = Math.abs(centerY - sourceY) + HALF_WIDTH;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static boolean waveHasReached(double sourceX, double sourceY, double radius,
                                         double centerX, double centerY) {
        return radius >= minDistance(sourceX, sourceY, centerX, centerY);
    }

    public static boolean waveHasPassed(double sourceX, double sourceY, double radius,
                                        double centerX, double centerY) {
        return radius > maxDistance(sourceX, sourceY, centerX, centerY);
    }

    public static boolean intersectsWave(double sourceX, double sourceY, double radius,
                                         double centerX, double centerY) {
        if (radius < 0.0) {
            return false;
        }
        double minDistance = minDistance(sourceX, sourceY, centerX, centerY);
        if (radius < minDistance) {
            return false;
        }
        return radius <= maxDistance(sourceX, sourceY, centerX, centerY);
    }

    public static double radialExtent(double absoluteBearing) {
        return HALF_WIDTH * (Math.abs(FastTrig.sin(absoluteBearing)) + Math.abs(FastTrig.cos(absoluteBearing)));
    }

    public static double[] angularInterval(double sourceX, double sourceY, double centerX, double centerY) {
        double dx = sourceX - centerX;
        double dy = sourceY - centerY;
        if (Math.abs(dx) <= HALF_WIDTH && Math.abs(dy) <= HALF_WIDTH) {
            return new double[]{0.0, FULL_CIRCLE};
        }

        double leftX = centerX - HALF_WIDTH;
        double rightX = centerX + HALF_WIDTH;
        double bottomY = centerY - HALF_WIDTH;
        double topY = centerY + HALF_WIDTH;
        double[] angles = new double[]{
                normalizeAnglePositive(FastTrig.atan2(leftX - sourceX, bottomY - sourceY)),
                normalizeAnglePositive(FastTrig.atan2(leftX - sourceX, topY - sourceY)),
                normalizeAnglePositive(FastTrig.atan2(rightX - sourceX, bottomY - sourceY)),
                normalizeAnglePositive(FastTrig.atan2(rightX - sourceX, topY - sourceY))
        };
        return intervalFromNormalizedAngles(angles, angles.length);
    }

    public static double[] guessFactorIntervalOffsets(double absoluteBearing, double distance, double mea) {
        double centerX = FastTrig.sin(absoluteBearing) * distance;
        double centerY = FastTrig.cos(absoluteBearing) * distance;
        double[] angularInterval = angularInterval(0.0, 0.0, centerX, centerY);
        return toGuessFactorInterval(angularInterval[0], angularInterval[1], absoluteBearing, mea);
    }

    public static double[] annulusAngularInterval(double sourceX, double sourceY,
                                                  double innerRadius, double outerRadius,
                                                  double centerX, double centerY) {
        if (outerRadius < 0.0 || outerRadius < innerRadius) {
            return null;
        }
        double clampedInnerRadius = Math.max(0.0, innerRadius);
        double minDist = minDistance(sourceX, sourceY, centerX, centerY);
        if (outerRadius < minDist) {
            return null;
        }
        double maxDist = maxDistance(sourceX, sourceY, centerX, centerY);
        if (clampedInnerRadius > maxDist) {
            return null;
        }
        return computeAnnulusAngularInterval(sourceX, sourceY, clampedInnerRadius, outerRadius, centerX, centerY);
    }

    /**
     * Variant that accepts pre-computed min/max distances to avoid redundant
     * Math.sqrt calls when the caller has already established overlap.
     */
    public static double[] annulusAngularInterval(double sourceX, double sourceY,
                                                  double innerRadius, double outerRadius,
                                                  double centerX, double centerY,
                                                  double precomputedMinDist,
                                                  double precomputedMaxDist) {
        double clampedInnerRadius = Math.max(0.0, innerRadius);
        if (outerRadius < precomputedMinDist || clampedInnerRadius > precomputedMaxDist) {
            return null;
        }
        return computeAnnulusAngularInterval(sourceX, sourceY, clampedInnerRadius, outerRadius, centerX, centerY);
    }

    private static double[] computeAnnulusAngularInterval(double sourceX, double sourceY,
                                                          double clampedInnerRadius, double outerRadius,
                                                          double centerX, double centerY) {
        double leftX = centerX - HALF_WIDTH;
        double rightX = centerX + HALF_WIDTH;
        double bottomY = centerY - HALF_WIDTH;
        double topY = centerY + HALF_WIDTH;
        double innerRadiusSq = clampedInnerRadius * clampedInnerRadius;
        double outerRadiusSq = outerRadius * outerRadius;

        double[] angles = new double[MAX_INTERSECTION_ANGLE_COUNT];
        int angleCount = 0;

        angleCount = addCornerAngleIfInAnnulus(
                sourceX, sourceY, leftX, bottomY, innerRadiusSq, outerRadiusSq, angles, angleCount);
        angleCount = addCornerAngleIfInAnnulus(
                sourceX, sourceY, leftX, topY, innerRadiusSq, outerRadiusSq, angles, angleCount);
        angleCount = addCornerAngleIfInAnnulus(
                sourceX, sourceY, rightX, bottomY, innerRadiusSq, outerRadiusSq, angles, angleCount);
        angleCount = addCornerAngleIfInAnnulus(
                sourceX, sourceY, rightX, topY, innerRadiusSq, outerRadiusSq, angles, angleCount);

        angleCount = addCircleVerticalIntersections(
                sourceX, sourceY, innerRadiusSq, leftX, bottomY, topY, angles, angleCount);
        angleCount = addCircleVerticalIntersections(
                sourceX, sourceY, innerRadiusSq, rightX, bottomY, topY, angles, angleCount);
        angleCount = addCircleHorizontalIntersections(
                sourceX, sourceY, innerRadiusSq, bottomY, leftX, rightX, angles, angleCount);
        angleCount = addCircleHorizontalIntersections(
                sourceX, sourceY, innerRadiusSq, topY, leftX, rightX, angles, angleCount);

        angleCount = addCircleVerticalIntersections(
                sourceX, sourceY, outerRadiusSq, leftX, bottomY, topY, angles, angleCount);
        angleCount = addCircleVerticalIntersections(
                sourceX, sourceY, outerRadiusSq, rightX, bottomY, topY, angles, angleCount);
        angleCount = addCircleHorizontalIntersections(
                sourceX, sourceY, outerRadiusSq, bottomY, leftX, rightX, angles, angleCount);
        angleCount = addCircleHorizontalIntersections(
                sourceX, sourceY, outerRadiusSq, topY, leftX, rightX, angles, angleCount);

        if (angleCount == 0) {
            return null;
        }
        return intervalFromNormalizedAngles(angles, angleCount);
    }

    public static double[] toGuessFactorInterval(double angleStart,
                                                 double angleEnd,
                                                 double referenceBearing,
                                                 double mea) {
        if (angleEnd < angleStart) {
            throw new IllegalArgumentException("Angular interval must be ordered");
        }
        double midpoint = (angleStart + angleEnd) * 0.5;
        double unwrappedReference = unwrapNear(referenceBearing, midpoint);
        return new double[]{
                (angleStart - unwrappedReference) / mea,
                (angleEnd - unwrappedReference) / mea
        };
    }

    private static double normalizeAnglePositive(double angle) {
        double normalized = angle % FULL_CIRCLE;
        if (normalized < 0.0) {
            normalized += FULL_CIRCLE;
        }
        return normalized;
    }

    private static double unwrapNear(double angle, double target) {
        return angle + Math.rint((target - angle) / FULL_CIRCLE) * FULL_CIRCLE;
    }

    private static double[] intervalFromNormalizedAngles(double[] angles, int angleCount) {
        Arrays.sort(angles, 0, angleCount);

        int largestGapIndex = 0;
        double largestGap = -1.0;
        for (int i = 0; i < angleCount; i++) {
            int nextIndex = (i + 1) % angleCount;
            double nextAngle = angles[nextIndex];
            if (nextIndex == 0) {
                nextAngle += FULL_CIRCLE;
            }
            double gap = nextAngle - angles[i];
            if (gap > largestGap) {
                largestGap = gap;
                largestGapIndex = i;
            }
        }

        int startIndex = (largestGapIndex + 1) % angleCount;
        double start = angles[startIndex];
        double end = angles[largestGapIndex];
        if (end < start) {
            end += FULL_CIRCLE;
        }
        return new double[]{start, end};
    }

    private static int addCornerAngleIfInAnnulus(double sourceX, double sourceY,
                                                 double pointX, double pointY,
                                                 double innerRadiusSq, double outerRadiusSq,
                                                 double[] angles, int angleCount) {
        double dx = pointX - sourceX;
        double dy = pointY - sourceY;
        double distanceSq = dx * dx + dy * dy;
        if (distanceSq < innerRadiusSq || distanceSq > outerRadiusSq) {
            return angleCount;
        }
        angles[angleCount] = normalizeAnglePositive(FastTrig.atan2(dx, dy));
        return angleCount + 1;
    }

    private static int addCircleVerticalIntersections(double sourceX, double sourceY,
                                                      double radiusSq,
                                                      double intersectX,
                                                      double yMin, double yMax,
                                                      double[] angles, int angleCount) {
        double deltaX = intersectX - sourceX;
        double rootSq = radiusSq - deltaX * deltaX;
        if (rootSq < 0.0) {
            return angleCount;
        }
        double root = Math.sqrt(Math.max(0.0, rootSq));
        double y1 = sourceY + root;
        if (y1 >= yMin && y1 <= yMax) {
            angles[angleCount++] = normalizeAnglePositive(FastTrig.atan2(deltaX, y1 - sourceY));
        }
        if (root > 0.0) {
            double y2 = sourceY - root;
            if (y2 >= yMin && y2 <= yMax) {
                angles[angleCount++] = normalizeAnglePositive(FastTrig.atan2(deltaX, y2 - sourceY));
            }
        }
        return angleCount;
    }

    private static int addCircleHorizontalIntersections(double sourceX, double sourceY,
                                                        double radiusSq,
                                                        double intersectY,
                                                        double xMin, double xMax,
                                                        double[] angles, int angleCount) {
        double deltaY = intersectY - sourceY;
        double rootSq = radiusSq - deltaY * deltaY;
        if (rootSq < 0.0) {
            return angleCount;
        }
        double root = Math.sqrt(Math.max(0.0, rootSq));
        double x1 = sourceX + root;
        if (x1 >= xMin && x1 <= xMax) {
            angles[angleCount++] = normalizeAnglePositive(FastTrig.atan2(x1 - sourceX, deltaY));
        }
        if (root > 0.0) {
            double x2 = sourceX - root;
            if (x2 >= xMin && x2 <= xMax) {
                angles[angleCount++] = normalizeAnglePositive(FastTrig.atan2(x2 - sourceX, deltaY));
            }
        }
        return angleCount;
    }
}

