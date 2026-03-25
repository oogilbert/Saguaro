package oog.mega.saguaro.mode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ModePerformanceProfile {
    // Opponent mode-performance records are disposable caches. When this layout changes, bump the
    // section version and let BattleDataStore discard stale files instead of maintaining migrations.
    private static final int SECTION_VERSION = 4;
    private static final int LEGACY_3_MODE_SECTION_VERSION = 3;
    private static final int LEGACY_3_MODE_BYTES = 16 * 3;
    private static final int MODE_BYTES = 16;
    private static final int SECTION_BYTES = MODE_BYTES * ModeId.values().length;
    private static final double MAX_RETAINED_TOTAL_SCORE = 1_000_000.0;

    private static final MutableModeStats[] persistedStats = createStatsArray();
    private static final MutableModeStats[] currentBattleStats = createStatsArray();

    private static String trackedOpponentName;
    private static boolean persistedModeStatsLoaded;

    private ModePerformanceProfile() {
    }

    public static void startBattle() {
        clearStats(persistedStats);
        clearStats(currentBattleStats);
        trackedOpponentName = null;
        persistedModeStatsLoaded = false;
    }

    public static int persistenceSectionVersion() {
        return SECTION_VERSION;
    }

    public static void ensureOpponentBaselineLoaded(String opponentName, int sectionVersion, byte[] payload) {
        if (opponentName == null || opponentName.isEmpty()) {
            throw new IllegalArgumentException("Mode performance profile requires a non-empty opponent name");
        }
        if (trackedOpponentName == null) {
            trackedOpponentName = opponentName;
            clearStats(persistedStats);
            persistedModeStatsLoaded = false;
            if (payload != null) {
                loadPersistedPayload(sectionVersion, payload);
            }
            return;
        }
        if (!trackedOpponentName.equals(opponentName)) {
            throw new IllegalStateException(
                    "Mode performance profile expected a single opponent but saw: "
                            + trackedOpponentName + " and " + opponentName);
        }
    }

    public static boolean hasPersistedSectionData() {
        return trackedOpponentName != null && (hasAnyData(persistedStats) || hasAnyData(currentBattleStats));
    }

    public static boolean isPersistedModeStatsLoaded() {
        return persistedModeStatsLoaded;
    }

    public static boolean hasAnyPersistedSamples() {
        return hasAnyData(persistedStats);
    }

    public static ModeStatsSnapshot getPersistedStats(ModeId modeId) {
        return snapshotFor(modeId, persistedStats);
    }

    public static ModeStatsSnapshot getCurrentBattleStats(ModeId modeId) {
        return snapshotFor(modeId, currentBattleStats);
    }

    public static ModeStatsSnapshot getCombinedStats(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Mode performance profile requires a non-null mode id");
        }
        MutableModeStats persisted = persistedStats[modeId.ordinal()];
        MutableModeStats current = currentBattleStats[modeId.ordinal()];
        return snapshotForTotals(
                modeId,
                persisted.totalOurScore + current.totalOurScore,
                persisted.totalOpponentScore + current.totalOpponentScore);
    }

    public static boolean hasAnyCombinedSamples(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Mode performance profile requires a non-null mode id");
        }
        MutableModeStats persisted = persistedStats[modeId.ordinal()];
        MutableModeStats current = currentBattleStats[modeId.ordinal()];
        return persisted.hasData() || current.hasData();
    }

    public static void recordSample(ModeId modeId,
                                    double rawOurScore,
                                    double rawOpponentScore,
                                    double adjustedOpponentScoreForShare) {
        if (modeId == null) {
            throw new IllegalArgumentException("Mode performance profile requires a non-null mode id");
        }
        if (!(rawOurScore >= 0.0) || !(rawOpponentScore >= 0.0) || !(adjustedOpponentScoreForShare >= 0.0)) {
            throw new IllegalArgumentException("Mode performance samples must be non-negative");
        }
        double denominator = rawOurScore + adjustedOpponentScoreForShare;
        if (!(denominator > 0.0)) {
            return;
        }
        double share = rawOurScore / denominator;
        if (!Double.isFinite(share)) {
            throw new IllegalStateException("Mode performance sample share must be finite");
        }
        currentBattleStats[modeId.ordinal()].addSample(rawOurScore, rawOpponentScore);
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes, boolean includeCurrentBattleData) {
        if (maxPayloadBytes < SECTION_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save mode-performance payload: payload budget="
                            + maxPayloadBytes + " bytes");
        }
        if (!hasAnyData(persistedStats) && (!includeCurrentBattleData || !hasAnyData(currentBattleStats))) {
            return null;
        }

        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            for (ModeId modeId : ModeId.values()) {
                ModeStatsSnapshot stats = includeCurrentBattleData
                        ? getCombinedStats(modeId)
                        : getPersistedStats(modeId);
                out.writeDouble(stats.totalOurScore);
                out.writeDouble(stats.totalOpponentScore);
            }
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length > maxPayloadBytes) {
                throw new IllegalStateException(
                        "Insufficient data quota to save mode-performance payload: required=" + payload.length
                                + " bytes, available=" + maxPayloadBytes + " bytes");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build mode-performance payload", e);
        }
    }

    private static void loadPersistedPayload(int sectionVersion, byte[] payload) {
        if (sectionVersion == LEGACY_3_MODE_SECTION_VERSION) {
            loadLegacy3ModePayload(payload);
            return;
        }
        if (sectionVersion != SECTION_VERSION) {
            throw new IllegalStateException("Unsupported mode-performance section version " + sectionVersion);
        }
        if (payload.length != SECTION_BYTES) {
            throw new IllegalStateException("Unexpected mode-performance payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            for (ModeId modeId : ModeId.values()) {
                double totalOurScore = in.readDouble();
                double totalOpponentScore = in.readDouble();
                validateLoadedStats(modeId, totalOurScore, totalOpponentScore);
                persistedStats[modeId.ordinal()].set(totalOurScore, totalOpponentScore);
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Mode-performance payload contained trailing bytes");
            }
            persistedModeStatsLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable mode-performance payload", e);
        }
    }

    private static void loadLegacy3ModePayload(byte[] payload) {
        if (payload.length != LEGACY_3_MODE_BYTES) {
            throw new IllegalStateException("Unexpected legacy 3-mode payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            ModeId[] allModes = ModeId.values();
            for (int i = 0; i < 3; i++) {
                double totalOurScore = in.readDouble();
                double totalOpponentScore = in.readDouble();
                validateLoadedStats(allModes[i], totalOurScore, totalOpponentScore);
                persistedStats[allModes[i].ordinal()].set(totalOurScore, totalOpponentScore);
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Legacy 3-mode payload contained trailing bytes");
            }
            persistedModeStatsLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable legacy 3-mode payload", e);
        }
    }

    private static void validateLoadedStats(ModeId modeId,
                                            double totalOurScore,
                                            double totalOpponentScore) {
        if (!Double.isFinite(totalOurScore) || !Double.isFinite(totalOpponentScore)) {
            throw new IllegalStateException("Mode-performance payload must contain finite values");
        }
        if (totalOurScore < 0.0 || totalOpponentScore < 0.0) {
            throw new IllegalStateException("Mode-performance payload must contain non-negative values");
        }
    }

    private static ModeStatsSnapshot snapshotFor(ModeId modeId, MutableModeStats[] source) {
        if (modeId == null) {
            throw new IllegalArgumentException("Mode performance profile requires a non-null mode id");
        }
        MutableModeStats stats = source[modeId.ordinal()];
        return snapshotForTotals(modeId, stats.totalOurScore, stats.totalOpponentScore);
    }

    private static ModeStatsSnapshot snapshotForTotals(ModeId modeId,
                                                       double totalOurScore,
                                                       double totalOpponentScore) {
        NormalizedTotals normalized = normalizeTotals(totalOurScore, totalOpponentScore);
        return new ModeStatsSnapshot(modeId, normalized.totalOurScore, normalized.totalOpponentScore);
    }

    private static boolean hasAnyData(MutableModeStats[] source) {
        for (int i = 0; i < source.length; i++) {
            if (source[i].hasData()) {
                return true;
            }
        }
        return false;
    }

    private static MutableModeStats[] createStatsArray() {
        MutableModeStats[] stats = new MutableModeStats[ModeId.values().length];
        for (ModeId modeId : ModeId.values()) {
            stats[modeId.ordinal()] = new MutableModeStats();
        }
        return stats;
    }

    private static void clearStats(MutableModeStats[] stats) {
        for (int i = 0; i < stats.length; i++) {
            stats[i].clear();
        }
    }

    private static NormalizedTotals normalizeTotals(double totalOurScore, double totalOpponentScore) {
        double totalScore = totalOurScore + totalOpponentScore;
        if (!(totalScore > MAX_RETAINED_TOTAL_SCORE)) {
            return new NormalizedTotals(totalOurScore, totalOpponentScore);
        }
        // Preserve the observed share while capping retained evidence so long-run totals stay numerically fresh.
        double scale = MAX_RETAINED_TOTAL_SCORE / totalScore;
        return new NormalizedTotals(totalOurScore * scale, totalOpponentScore * scale);
    }

    private static final class MutableModeStats {
        double totalOurScore;
        double totalOpponentScore;

        void addSample(double rawOurScore, double rawOpponentScore) {
            totalOurScore += rawOurScore;
            totalOpponentScore += rawOpponentScore;
        }

        void clear() {
            totalOurScore = 0.0;
            totalOpponentScore = 0.0;
        }

        void set(double totalOurScore, double totalOpponentScore) {
            this.totalOurScore = totalOurScore;
            this.totalOpponentScore = totalOpponentScore;
        }

        boolean hasData() {
            return totalOurScore > 0.0 || totalOpponentScore > 0.0;
        }
    }

    public static final class ModeStatsSnapshot {
        public final ModeId modeId;
        public final double totalOurScore;
        public final double totalOpponentScore;

        private ModeStatsSnapshot(ModeId modeId,
                                  double totalOurScore,
                                  double totalOpponentScore) {
            this.modeId = modeId;
            this.totalOurScore = totalOurScore;
            this.totalOpponentScore = totalOpponentScore;
        }

        public boolean hasSamples() {
            return totalOurScore > 0.0 || totalOpponentScore > 0.0;
        }

        public double aggregateRawShare() {
            double denominator = totalOurScore + totalOpponentScore;
            return denominator > 0.0 ? totalOurScore / denominator : Double.NaN;
        }
    }

    private static final class NormalizedTotals {
        final double totalOurScore;
        final double totalOpponentScore;

        NormalizedTotals(double totalOurScore, double totalOpponentScore) {
            this.totalOurScore = totalOurScore;
            this.totalOpponentScore = totalOpponentScore;
        }
    }
}
