package oog.mega.saguaro.info.learning.rule;

import robocode.util.Utils;

/** Speed-bounded wave crossings from a position/time, without a movement path. */
public final class WaveReachability {
    /**
     * Whether a wave fired from a point can still be ahead of an anchor.
     *
     * At very close range a predicted crossing can lie at or inside the front, which
     * leaves this geometry undefined. That is ordinary game state rather than a
     * programming error, so callers test for it and skip the branch; the constructor
     * still refuses to build an undefined one.
     */
    public static boolean feasible(double sourceX, double sourceY, double speed,
                                   double fireTime, Position start) {
        if (speed <= 8.0) return false;
        double radius = Math.max(0.0, (start.time() - fireTime) * speed);
        return oog.mega.saguaro.math.MathUtils.hypot(start.x() - sourceX, start.y() - sourceY) > radius;
    }

    public record Position(double x, double y, double time) { }
    public record RadialRange(double lower,double upper) { }
    public final double bearing;
    public final double minOffset;
    public final double maxOffset;
    private final double sourceX, sourceY, speed, fireTime, width, height, radius;
    private final Position start;
    private final double kSquared;

    public WaveReachability(double sourceX, double sourceY, double speed, double fireTime,
                            Position start, double width, double height) {
        this.sourceX = sourceX;
        this.sourceY = sourceY;
        this.speed = speed;
        this.fireTime = fireTime;
        this.start = start;
        this.width = width;
        this.height = height;
        radius = Math.max(0.0, (start.time() - fireTime) * speed);
        kSquared = 64.0 / (speed * speed);
        double distance = oog.mega.saguaro.math.MathUtils.hypot(start.x() - sourceX, start.y() - sourceY);
        if (!feasible(sourceX, sourceY, speed, fireTime, start)) {
            throw new IllegalArgumentException("Wave must still be ahead of the crossing anchor");
        }
        bearing = Math.atan2(start.x() - sourceX, start.y() - sourceY);
        // Tangency of |source + d*u - start| <= (8/speed)*(d-radius).
        double cosine = (kSquared * radius
                + Math.sqrt((1.0 - kSquared) * (distance * distance - kSquared * radius * radius)))
                / distance;
        double extent = Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
        minOffset = clippedExtent(-extent);
        maxOffset = clippedExtent(extent);
    }

    private double clippedExtent(double edge) {
        if (radialRange(bearing + edge) != null) return edge;
        double feasible = 0.0;
        double infeasible = edge;
        // Clip the angular envelope to the rectangular field. This is a scalar
        // geometry search, not time-stepping or an assumed steering trajectory.
        // 32 halvings bound angular error below 4e-10 radians; further
        // iterations add work without useful battlefield-scale precision.
        for (int i = 0; i < 32; i++) {
            double middle = (feasible + infeasible) * 0.5;
            if (radialRange(bearing + middle) != null) feasible = middle;
            else infeasible = middle;
        }
        return feasible;
    }

    /** Returns a reachable center and its wave crossing time, or null. */
    public Position pointAt(double angle) {
        return pointAt(angle, Double.NaN);
    }

    /** Preserve the requested ray while restricting a preferred radial distance. */
    public Position pointAt(double angle, double preferredRadius) {
        double ux=Math.sin(angle),uy=Math.cos(angle);
        RadialRange range=radialRange(ux,uy);
        if(range==null) return null;
        double projected=Double.isFinite(preferredRadius) ? preferredRadius
                : (start.x()-sourceX)*ux+(start.y()-sourceY)*uy;
        double distance=Math.max(range.lower(),Math.min(range.upper(),projected));
        return new Position(sourceX+ux*distance,sourceY+uy*distance,fireTime+distance/speed);
    }

    /** All speed-feasible radial distances along one ray, including field clipping. */
    public RadialRange radialRange(double angle) {
        return radialRange(Math.sin(angle),Math.cos(angle));
    }
    /** Reuse a ray direction across intersecting analytic regions. */
    public RadialRange radialRange(double ux,double uy) {
        double dx = sourceX - start.x();
        double dy = sourceY - start.y();
        double a = 1.0 - kSquared;
        double b = 2.0 * (dx * ux + dy * uy + kSquared * radius);
        double c = dx * dx + dy * dy - kSquared * radius * radius;
        double discriminant = b * b - 4.0 * a * c;
        double tolerance = 1e-12 * Math.max(1.0, b * b + Math.abs(4.0 * a * c));
        if (discriminant < -tolerance) return null;
        double root = Math.sqrt(Math.max(0.0, discriminant));
        double low = Math.max(radius, (-b - root) / (2.0 * a));
        double high = (-b + root) / (2.0 * a);
        double[] xClip = rayClip(sourceX, ux, 18.0, width - 18.0);
        double[] yClip = rayClip(sourceY, uy, 18.0, height - 18.0);
        if (xClip == null || yClip == null) return null;
        low = Math.max(low, Math.max(xClip[0], yClip[0]));
        high = Math.min(high, Math.min(xClip[1], yClip[1]));
        if (low > high + 1e-8 || high < 0.0) return null;
        return new RadialRange(low,Math.max(low,high));
    }

    private static double[] rayClip(double origin, double direction, double low, double high) {
        if (Math.abs(direction) < 1e-14) {
            return origin >= low - 1e-9 && origin <= high + 1e-9
                    ? new double[]{Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        }
        double first = (low - origin) / direction;
        double second = (high - origin) / direction;
        return new double[]{Math.min(first, second), Math.max(first, second)};
    }

    public boolean contains(double angle) {
        double offset = Utils.normalRelativeAngle(angle - bearing);
        return offset >= minOffset && offset <= maxOffset && pointAt(angle) != null;
    }
}
