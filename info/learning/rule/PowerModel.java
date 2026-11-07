package oog.mega.saguaro.info.learning.rule;

/**
 * How one side chooses its bullet power: the power it fired, as a function of the range,
 * its own energy, its target's energy, how well it has been hitting, and the power its
 * target has lately been firing back -- the inputs bots' power rules read, the last of them
 * by the strongest bots, which answer heavy fire with heavy fire and light with light.
 *
 * Predicted by kernel regression over the shots it has fired this battle, so a rule of any
 * shape in range, energy and hit rate is reproduced where it has been observed. In the
 * target's power it is locally linear, with a prior slope of zero: a target's power is
 * nearly constant within a battle, and a response to powers it has not fired can only be
 * read off the slope across the ones it has.
 */
final class PowerModel {
    /** Beyond about three recency scales a shot's weight is under 1%: a hundred is all of it. */
    private static final int CAPACITY = 100;
    /** Per-feature kernel scales: 100 px of range, 10 energy, 3% hit rate. */
    private static final double[] SCALE = {1 / 100.0, 1 / 10.0, 1 / 10.0, 1 / 0.03};
    /**
     * Recency, in shots: a rule drifts as the shooter's state changes, and the shots it
     * fired lately say more about its next than ones from rounds ago.
     */
    private static final double RECENCY = 30;
    /** Hit-rate memory: about fifty shots. */
    private static final double RETENTION = 0.98;
    /**
     * The prior on the response to the target's power, as the ratio of a shot's power noise
     * to the prior spread of the slope: about 0.1 against 0.5.
     */
    private static final double SLOPE_PRIOR = 0.04;
    private static final int FEATURES = SCALE.length + 1;
    private static final int TARGET_POWER = SCALE.length;

    private final double[] features = new double[CAPACITY * FEATURES];
    private final double[] powers = new double[CAPACITY];
    private final long[] ordinals = new long[CAPACITY];
    private long fired;
    /**
     * Predictions are read many times a tick from the planner's simulations; they are kept
     * until the model next changes: range to 10 px, energies to 2 and the target's power to
     * 0.05, a tenth and a fifth of the kernel's own scales and a fraction of a step in power.
     */
    private static final int MEMO = 1 << 14;
    private final long[] memoKeys = new long[MEMO];
    private final double[] memoValues = new double[MEMO];
    private final boolean[] memoUsed = new boolean[MEMO];
    private int memoSize;

    private void clearMemo() {
        java.util.Arrays.fill(memoUsed, false);
        memoSize = 0;
    }
    private int size, next;
    private double hits, shots;
    /** The situation of the last kernel fit, whose slope the diagnostics ask for. */
    private final double[] lastQuery = new double[4];
    private boolean hasQuery;

    PowerModel() { clear(); }

    void clear() {
        size = 0;
        next = 0;
        fired = 0;
        clearMemo();
        lastPower = Double.NaN;
        // A tenth, worth five shots, until its own record says otherwise.
        hits = 0.5;
        shots = 5;
    }

    double hitRate() { return hits / shots; }

    /** The last shot's power and situation, which a prediction is made relative to. */
    private double lastPower = Double.NaN, lastDistance, lastShooterEnergy, lastTargetEnergy, lastTargetPower;
    /** The rule's own value at the last shot's situation, until the model next changes. */
    private double lastLevel = Double.NaN;
    private long lastLevelRate = Long.MIN_VALUE;

    /**
     * The power it would fire in a situation: what it fired last, moved by how its rule
     * changes between that shot's situation and this one. A shooter's power persists from
     * shot to shot beyond anything its situation explains, so the rule is used for the
     * difference a situation makes, not for the level. NaN before any shot.
     */
    double predictFromLast(double distance, double shooterEnergy, double targetEnergy, double targetPower) {
        if (!Double.isFinite(lastPower)) return Double.NaN;
        if (!Double.isFinite(lastLevel) || lastLevelRate != Math.round(hitRate() / 0.003)) {
            lastLevel = predict(lastDistance, lastShooterEnergy, lastTargetEnergy, lastTargetPower);
            lastLevelRate = Math.round(hitRate() / 0.003);
        }
        double change = predict(distance, shooterEnergy, targetEnergy, targetPower) - lastLevel;
        return Math.max(0.1, Math.min(3.0, lastPower + change));
    }

    /** A shot was fired: the power, in the situation it was fired in. */
    void onShot(double distance, double shooterEnergy, double targetEnergy, double targetPower, double power) {
        if (!(power >= 0.1) || !Double.isFinite(distance)) return;
        if (!Double.isFinite(targetPower)) targetPower = 0;
        lastPower = power;
        lastDistance = distance;
        lastShooterEnergy = shooterEnergy;
        lastTargetEnergy = targetEnergy;
        lastTargetPower = targetPower;
        int base = next * FEATURES;
        features[base] = distance;
        features[base + 1] = shooterEnergy;
        features[base + 2] = targetEnergy;
        features[base + 3] = hitRate();
        features[base + TARGET_POWER] = targetPower;
        powers[next] = power;
        ordinals[next] = fired++;
        next = (next + 1) % CAPACITY;
        clearMemo();
        lastLevel = Double.NaN;
        size = Math.min(CAPACITY, size + 1);
    }

    /** One of the shooter's shots resolved. */
    void onOutcome(boolean hit) {
        hits = RETENTION * hits + (hit ? 1 : 0);
        shots = RETENTION * shots + 1;
    }

    /** The power it would fire in a situation, at its present hit rate; NaN before any shot. */
    double predict(double distance, double shooterEnergy, double targetEnergy, double targetPower) {
        if (size == 0) return Double.NaN;
        // The target's power does not move a prediction while its slope is unused (see
        // kernel), so it is left out of the key and the memo keeps hitting.
        targetPower = 0;
        long rate = Math.round(hitRate() / 0.003);
        long key = (Math.round(distance / 10) << 44) ^ (Math.round(shooterEnergy / 2) << 34)
                ^ (Math.round(targetEnergy / 2) << 24) ^ rate;
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 50);
        while (memoUsed[slot]) {
            if (memoKeys[slot] == key) return memoValues[slot];
            slot = (slot + 1) & (MEMO - 1);
        }
        double value = kernel(Math.round(distance / 10) * 10.0, Math.round(shooterEnergy / 2) * 2.0,
                Math.round(targetEnergy / 2) * 2.0, rate * 0.003, Math.round(targetPower / 0.05) * 0.05);
        if (memoSize > MEMO / 2) {
            clearMemo();
            slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 50);
        }
        memoUsed[slot] = true;
        memoKeys[slot] = key;
        memoValues[slot] = value;
        memoSize++;
        return value;
    }

    /** The fitted response to the target's power at the last kernel fit. */
    double lastSlope() {
        if (!hasQuery || size == 0) return 0;
        double[] w = weights(lastQuery[0], lastQuery[1], lastQuery[2], lastQuery[3]);
        double weight = 0, sx = 0, sy = 0;
        for (int i = 0; i < size; i++) {
            weight += w[i];
            sx += w[i] * features[i * FEATURES + TARGET_POWER];
            sy += w[i] * powers[i];
        }
        double mx = sx / weight, my = sy / weight;
        double sxx = 0, sxy = 0;
        for (int i = 0; i < size; i++) {
            double dx = features[i * FEATURES + TARGET_POWER] - mx;
            sxx += w[i] * dx * dx;
            sxy += w[i] * dx * (powers[i] - my);
        }
        // Fitted for diagnostics only. Within a battle the target's power moves with the
        // energies and the round's phase, which drive the shooter's power too, so this slope
        // measures that confound rather than the response: against DrussGT it wandered from
        // +0.1 to -1.9 in one battle while across battles its power follows ours at about
        // +0.8. Using it pushed ScoreMax towards heavier fire, the wrong way.
        return sxy / (sxx + SLOPE_PRIOR);
    }

    private double kernel(double distance, double shooterEnergy, double targetEnergy, double rate,
                          double targetPower) {
        lastQuery[0] = distance; lastQuery[1] = shooterEnergy; lastQuery[2] = targetEnergy; lastQuery[3] = rate;
        hasQuery = true;
        double[] w = weights(distance, shooterEnergy, targetEnergy, rate);
        double weight = 0, sy = 0;
        for (int i = 0; i < size; i++) {
            weight += w[i];
            sy += w[i] * powers[i];
        }
        return sy / weight;
    }

    /** Each shot's kernel weight for a situation, the nearest at one, in the scratch buffer. */
    private double[] weights(double distance, double shooterEnergy, double targetEnergy, double rate) {
        double nearest = Double.POSITIVE_INFINITY;
        double[] squared = scratch(size);
        for (int i = 0; i < size; i++) {
            int base = i * FEATURES;
            double a = (distance - features[base]) * SCALE[0];
            double b = (shooterEnergy - features[base + 1]) * SCALE[1];
            double c = (targetEnergy - features[base + 2]) * SCALE[2];
            double d = (rate - features[base + 3]) * SCALE[3];
            double e = (fired - ordinals[i]) / RECENCY;
            double s = a * a + b * b + c * c + d * d + e * e;
            squared[i] = s;
            if (s < nearest) nearest = s;
        }
        for (int i = 0; i < size; i++) squared[i] = Math.exp(-0.5 * (squared[i] - nearest));
        return squared;
    }

    private double[] buffer = new double[0];

    private double[] scratch(int n) {
        if (buffer.length < n) buffer = new double[CAPACITY];
        return buffer;
    }

    int size() { return size; }
}
