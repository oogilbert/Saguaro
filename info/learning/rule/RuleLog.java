package oog.mega.saguaro.info.learning.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Observations one side has made of the other: the context features of the situation
 * a wave was fired in, the canonical guess factor of what happened on it, and when it
 * became known.
 *
 * A hit is a wave whose bullet reached its target; a pass is one that went by without;
 * a collision is a bullet seen to meet another. Which way each kind pushes the opponent
 * is learned by the model reading the log -- the log only records what was seen.
 */
public final class RuleLog {
    public enum Kind { HIT, PASSED, COLLIDED }

    /**
     * One observation.
     *
     * @param time    battle-clock tick it became known, so the model can be read as of
     *                any earlier instant with exactly the evidence that existed then
     * @param ordinal position in its own sequence -- hits and collisions share one,
     *                passes have their own -- which recency is measured in
     */
    public record Entry(double[] features, double guessFactor, Kind kind, long time, long ordinal) { }

    /** Which recency sequence a kind belongs to: 0 for revealed bullets, 1 for passes. */
    public static int sequence(Kind kind) { return kind == Kind.PASSED ? 1 : 0; }

    private final List<Entry> entries = new ArrayList<>();
    private final View view = new View();

    /**
     * The entries, read-only, with the same data laid out flat for the model's
     * nearest-neighbour scan, which reads every entry on every query.
     */
    final class View extends java.util.AbstractList<Entry> {
        @Override public Entry get(int index) { return entries.get(index); }
        @Override public int size() { return entries.size(); }
        RuleLog owner() { return RuleLog.this; }
    }

    /** Entry i's features at [i * featureCount, (i + 1) * featureCount), in entry order. */
    double[] flatFeatures;
    long[] flatTimes, flatOrdinals;
    byte[] flatSequences;
    private final int capacity;
    private final int featureCount;
    private final long[] counts = new long[2];
    /**
     * Robocode's tick restarts every round, so an observation's round tick says nothing
     * about whether it came before a later round's read. Each round's ticks are offset
     * past everything already logged.
     */
    private long offset;
    private long latest = -1;

    public RuleLog(int capacity, int featureCount) {
        this.capacity = capacity;
        this.featureCount = featureCount;
        flatFeatures = new double[(capacity + 1) * featureCount];
        flatTimes = new long[capacity + 1];
        flatOrdinals = new long[capacity + 1];
        flatSequences = new byte[capacity + 1];
    }

    int featureCount() { return featureCount; }

    public List<Entry> entries() { return view; }
    public int size() { return entries.size(); }

    /** A round tick on the battle-long clock. */
    public long clock(long roundTime) {
        return roundTime == Long.MAX_VALUE ? Long.MAX_VALUE : roundTime + offset;
    }

    /** Start a round: its ticks come after everything logged so far. */
    public void newRound() {
        offset = Math.max(offset, latest + 1);
    }

    /** Changes with every addition, so a reader's derived copy knows when it is stale. */
    long version() { return version; }
    private long version;

    public void add(double[] features, double guessFactor, Kind kind, long roundTime) {
        if (features == null || features.length != featureCount) return;
        if (!Double.isFinite(guessFactor)) return;
        version++;
        long stamped = clock(roundTime);
        latest = Math.max(latest, stamped);
        Entry entry = new Entry(features.clone(), Math.max(-1, Math.min(1, guessFactor)), kind,
                stamped, counts[sequence(kind)]++);
        int at = entries.size();
        entries.add(entry);
        System.arraycopy(entry.features(), 0, flatFeatures, at * featureCount, featureCount);
        flatTimes[at] = entry.time();
        flatOrdinals[at] = entry.ordinal();
        flatSequences[at] = (byte) sequence(kind);
        while (entries.size() > capacity) {
            entries.remove(0);
            int n = entries.size();
            System.arraycopy(flatFeatures, featureCount, flatFeatures, 0, n * featureCount);
            System.arraycopy(flatTimes, 1, flatTimes, 0, n);
            System.arraycopy(flatOrdinals, 1, flatOrdinals, 0, n);
            System.arraycopy(flatSequences, 1, flatSequences, 0, n);
        }
    }

    /** Kept across rounds; cleared only for a new battle. */
    public void clearBattle() {
        version++;
        entries.clear();
        counts[0] = 0;
        counts[1] = 0;
        offset = 0;
        latest = -1;
    }
}
