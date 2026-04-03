package oog.mega.saguaro.mode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ModePerformanceProfile {
    // Opponent mode-performance records are disposable caches. When this layout changes, bump the
    // section version and let BattleDataStore discard stale files instead of maintaining migrations.
    private static final int SECTION_VERSION = 11;
    private static final int MODE_BYTES = Short.BYTES * 2;
    private static final int SECTION_BYTES = MODE_BYTES * ModeId.values().length;
    private static final int LOCKED_MODE_SECTION_BYTES = 1 + MODE_BYTES;
    private static final double MAX_RETAINED_TOTAL_SCORE = 1_000_000.0;
    private static final double PERSISTED_UNSIGNED_SHORT_MAX = 65535.0;
    private static final double PERSISTED_POSITIVE_TOTAL_SCORE_STEPS = 65534.0;
    private static final double MIN_PERSISTED_POSITIVE_TOTAL_SCORE = 1e-6;

    private static final MutableModeStats[] persistedStats = createStatsArray();
    private static final MutableModeStats[] currentBattleStats = createStatsArray();

    private static String trackedOpponentName;
    private static boolean persistedModeStatsLoaded;
    private static ModeId persistedLockedModeId;

    private ModePerformanceProfile() {
    }

    public static void startBattle() {
        clearStats(persistedStats);
        clearStats(currentBattleStats);
        trackedOpponentName = null;
        persistedModeStatsLoaded = false;
        persistedLockedModeId = null;
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

    public static ModeId getPersistedLockedMode() {
        return persistedLockedModeId;
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
        return createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData, null);
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes,
                                                       boolean includeCurrentBattleData,
                                                       ModeId lockedModeId) {
        if (lockedModeId != null) {
            return createLockedModePayload(maxPayloadBytes, includeCurrentBattleData, lockedModeId);
        }
        return createFullPayload(maxPayloadBytes, includeCurrentBattleData);
    }

    private static byte[] createFullPayload(int maxPayloadBytes, boolean includeCurrentBattleData) {
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
                writePersistedStats(out, stats);
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

    private static byte[] createLockedModePayload(int maxPayloadBytes,
                                                  boolean includeCurrentBattleData,
                                                  ModeId lockedModeId) {
        if (lockedModeId == null) {
            throw new IllegalArgumentException("Locked-mode payload requires a non-null mode id");
        }
        if (maxPayloadBytes < LOCKED_MODE_SECTION_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save locked mode-performance payload: payload budget="
                            + maxPayloadBytes + " bytes");
        }
        ModeStatsSnapshot stats = includeCurrentBattleData
                ? getCombinedStats(lockedModeId)
                : getPersistedStats(lockedModeId);
        if (!stats.hasSamples()) {
            return null;
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(lockedModeId.ordinal());
            writePersistedStats(out, stats);
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length > maxPayloadBytes) {
                throw new IllegalStateException(
                        "Insufficient data quota to save locked mode-performance payload: required=" + payload.length
                                + " bytes, available=" + maxPayloadBytes + " bytes");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build locked mode-performance payload", e);
        }
    }

    private static void loadPersistedPayload(int sectionVersion, byte[] payload) {
        if (sectionVersion != SECTION_VERSION) {
            throw new IllegalStateException("Unsupported mode-performance section version " + sectionVersion);
        }
        loadCurrentPayload(payload);
    }

    private static void loadCurrentPayload(byte[] payload) {
        if (payload.length == SECTION_BYTES) {
            loadFullPayload(payload);
            return;
        }
        if (payload.length == LOCKED_MODE_SECTION_BYTES) {
            loadLockedModePayload(payload);
            return;
        }
        throw new IllegalStateException("Unexpected mode-performance payload length");
    }

    private static void loadFullPayload(byte[] payload) {
        if (payload.length != SECTION_BYTES) {
            throw new IllegalStateException("Unexpected mode-performance payload length");
        }
        clearStats(persistedStats);
        persistedLockedModeId = null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            for (ModeId modeId : ModeId.values()) {
                DecodedPersistedStats decoded = readPersistedStats(in);
                validateLoadedStats(modeId, decoded.totalOurScore, decoded.totalOpponentScore);
                persistedStats[modeId.ordinal()].set(decoded.totalOurScore, decoded.totalOpponentScore);
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Mode-performance payload contained trailing bytes");
            }
            persistedModeStatsLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable mode-performance payload", e);
        }
    }

    private static void loadLockedModePayload(byte[] payload) {
        if (payload.length != LOCKED_MODE_SECTION_BYTES) {
            throw new IllegalStateException("Unexpected locked mode-performance payload length");
        }
        clearStats(persistedStats);
        persistedLockedModeId = null;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int modeOrdinal = in.readUnsignedByte();
            if (modeOrdinal < 0 || modeOrdinal >= ModeId.values().length) {
                throw new IllegalStateException("Locked mode-performance payload contained invalid mode ordinal");
            }
            ModeId modeId = ModeId.values()[modeOrdinal];
            DecodedPersistedStats decoded = readPersistedStats(in);
            validateLoadedStats(modeId, decoded.totalOurScore, decoded.totalOpponentScore);
            if (decoded.totalOurScore <= 0.0 && decoded.totalOpponentScore <= 0.0) {
                throw new IllegalStateException("Locked mode-performance payload must contain non-zero totals");
            }
            persistedStats[modeId.ordinal()].set(decoded.totalOurScore, decoded.totalOpponentScore);
            if (in.available() != 0) {
                throw new IllegalStateException("Locked mode-performance payload contained trailing bytes");
            }
            persistedLockedModeId = modeId;
            persistedModeStatsLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable locked mode-performance payload", e);
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

    private static void writePersistedStats(DataOutputStream out,
                                            ModeStatsSnapshot stats) throws IOException {
        if (stats == null || !stats.hasSamples()) {
            out.writeShort(0);
            out.writeShort(0);
            return;
        }
        out.writeShort(quantizeShare(stats.aggregateRawShare()));
        out.writeShort(quantizeTotalScore(stats.totalOurScore + stats.totalOpponentScore));
    }

    private static DecodedPersistedStats readPersistedStats(DataInputStream in) throws IOException {
        int shareQuantized = in.readUnsignedShort();
        int totalQuantized = in.readUnsignedShort();
        if (totalQuantized == 0) {
            return new DecodedPersistedStats(0.0, 0.0);
        }
        double totalScore = dequantizeTotalScore(totalQuantized);
        double share = dequantizeLinear(shareQuantized, 0.0, 1.0);
        double totalOurScore = totalScore * share;
        double totalOpponentScore = Math.max(0.0, totalScore - totalOurScore);
        return new DecodedPersistedStats(totalOurScore, totalOpponentScore);
    }

    private static int quantizeShare(double share) {
        return quantizeLinear(share, 0.0, 1.0);
    }

    private static int quantizeTotalScore(double totalScore) {
        if (!(totalScore > 0.0)) {
            return 0;
        }
        double clamped = clamp(totalScore, MIN_PERSISTED_POSITIVE_TOTAL_SCORE, MAX_RETAINED_TOTAL_SCORE);
        double normalized = (Math.log(clamped) - Math.log(MIN_PERSISTED_POSITIVE_TOTAL_SCORE))
                / (Math.log(MAX_RETAINED_TOTAL_SCORE) - Math.log(MIN_PERSISTED_POSITIVE_TOTAL_SCORE));
        return 1 + (int) Math.round(normalized * PERSISTED_POSITIVE_TOTAL_SCORE_STEPS);
    }

    private static double dequantizeTotalScore(int quantized) {
        if (quantized <= 0) {
            return 0.0;
        }
        double normalized = clamp(quantized - 1.0, 0.0, PERSISTED_POSITIVE_TOTAL_SCORE_STEPS)
                / PERSISTED_POSITIVE_TOTAL_SCORE_STEPS;
        double logTotal = Math.log(MIN_PERSISTED_POSITIVE_TOTAL_SCORE)
                + (Math.log(MAX_RETAINED_TOTAL_SCORE) - Math.log(MIN_PERSISTED_POSITIVE_TOTAL_SCORE)) * normalized;
        return Math.exp(logTotal);
    }

    private static int quantizeLinear(double value, double min, double max) {
        if (!(max > min)) {
            return 0;
        }
        double normalized = (clamp(value, min, max) - min) / (max - min);
        return (int) Math.round(normalized * PERSISTED_UNSIGNED_SHORT_MAX);
    }

    private static double dequantizeLinear(int quantized, double min, double max) {
        if (!(max > min)) {
            return min;
        }
        double clamped = clamp(quantized, 0.0, PERSISTED_UNSIGNED_SHORT_MAX);
        return min + (max - min) * (clamped / PERSISTED_UNSIGNED_SHORT_MAX);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private static final class DecodedPersistedStats {
        final double totalOurScore;
        final double totalOpponentScore;

        DecodedPersistedStats(double totalOurScore, double totalOpponentScore) {
            this.totalOurScore = totalOurScore;
            this.totalOpponentScore = totalOpponentScore;
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
