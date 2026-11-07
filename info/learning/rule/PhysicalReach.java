package oog.mega.saguaro.info.learning.rule;

import robocode.Rules;

/**
 * Exact one-dimensional displacement bound from Robocode's own velocity rules.
 *
 * Along any fixed direction a robot's velocity component gains at most
 * {@link Rules#DECELERATION} per turn while that component is still negative
 * and at most {@link Rules#ACCELERATION} per turn afterwards, capped at
 * {@link Rules#MAX_VELOCITY}. Reversing needs no turning in Robocode, so this
 * is a property of the engine rather than of any opponent's steering: a robot
 * already travelling away from a direction must spend whole turns braking
 * before it can make progress in it.
 *
 * The isotropic speed-8 ball used elsewhere ignores this and is far too loose
 * on the reversal side. This is a necessary condition only; it predicts no
 * controls and models no behaviour. Velocity updates are applied per whole
 * turn, and any fractional turn is charged at full speed so the result stays
 * an upper bound.
 */
public final class PhysicalReach {
    private PhysicalReach() { }

    /** Sum of min(cap, start + step*i) for i in 1..turns, in closed form. */
    private static double rampSum(double start, double step, double cap, long turns) {
        if (turns <= 0) return 0;
        long ramp = turns;
        if (step > 0) {
            double exact = Math.ceil((cap - start) / step) - 1;
            ramp = (long) Math.max(0, Math.min(turns, exact));
        }
        return ramp * start + step * ramp * (ramp + 1) * 0.5 + (turns - ramp) * cap;
    }

    /**
     * Greatest displacement along a direction achievable within {@code turns}
     * turns starting from velocity component {@code velocity} along it.
     * Negative velocity means currently travelling the opposite way.
     */
    public static double maxDisplacement(double velocity, double turns) {
        if (!Double.isFinite(velocity) || !Double.isFinite(turns)) {
            throw new IllegalArgumentException("Finite velocity and turns required");
        }
        if (!(turns > 0)) return 0;
        double v = Math.max(-Rules.MAX_VELOCITY, Math.min(Rules.MAX_VELOCITY, velocity));
        long whole = (long) Math.floor(turns);
        double fraction = turns - whole;
        // Whole turns where the component is still negative gain DECELERATION.
        long braking = v < 0 ? (long) Math.min(whole, Math.ceil(-v / Rules.DECELERATION)) : 0;
        double distance = braking * v + Rules.DECELERATION * braking * (braking + 1) * 0.5;
        double after = v + Rules.DECELERATION * braking;
        distance += rampSum(after, Rules.ACCELERATION, Rules.MAX_VELOCITY, whole - braking);
        // Charge any partial turn at the cap to keep this an upper bound.
        return distance + fraction * Rules.MAX_VELOCITY;
    }

    /** Necessary condition for covering an offset within the given time. */
    public static boolean reachable(double dx, double dy, double vx, double vy, double turns) {
        double distance = oog.mega.saguaro.math.MathUtils.hypot(dx, dy);
        if (distance <= 1e-9) return true;
        double projection = (vx * dx + vy * dy) / distance;
        return distance <= maxDisplacement(projection, turns) + 1e-9;
    }
}
