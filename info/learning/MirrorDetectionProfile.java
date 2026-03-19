package oog.mega.saguaro.info.learning;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class MirrorDetectionProfile {
    // Opponent mirror-detection records are compact caches. When this layout changes, newer code
    // may ignore the stale section, preserve the file, and disable saves for the battle.
    private static final int SECTION_VERSION = 2;
    private static final int SECTION_BYTES = 21;

    private static String trackedOpponentName;
    private static boolean persistedMirrorDetectionLoaded;
    private static int persistedSampleCount;
    private static double persistedTotalError;
    private static double persistedTotalSquaredError;
    private static boolean persistedEnabledEver;
    private static int currentBattleSampleCount;
    private static double currentBattleTotalError;
    private static double currentBattleTotalSquaredError;
    private static boolean currentBattleEnabledEver;

    private MirrorDetectionProfile() {
    }

    public static void startBattle() {
        trackedOpponentName = null;
        persistedMirrorDetectionLoaded = false;
        persistedSampleCount = 0;
        persistedTotalError = 0.0;
        persistedTotalSquaredError = 0.0;
        persistedEnabledEver = false;
        currentBattleSampleCount = 0;
        currentBattleTotalError = 0.0;
        currentBattleTotalSquaredError = 0.0;
        currentBattleEnabledEver = false;
    }

    public static int persistenceSectionVersion() {
        return SECTION_VERSION;
    }

    public static void ensureOpponentBaselineLoaded(String opponentName, int sectionVersion, byte[] payload) {
        if (opponentName == null || opponentName.isEmpty()) {
            throw new IllegalArgumentException("Mirror detection requires a non-empty opponent name");
        }
        if (trackedOpponentName == null) {
            trackedOpponentName = opponentName;
            persistedMirrorDetectionLoaded = false;
            persistedSampleCount = 0;
            persistedTotalError = 0.0;
            persistedTotalSquaredError = 0.0;
            persistedEnabledEver = false;
            if (payload != null) {
                loadPersistedPayload(sectionVersion, payload);
            }
            return;
        }
        if (!trackedOpponentName.equals(opponentName)) {
            throw new IllegalStateException(
                    "Mirror detection expected a single opponent but saw: "
                            + trackedOpponentName + " and " + opponentName);
        }
    }

    public static void recordPredictionError(double error) {
        if (trackedOpponentName == null) {
            throw new IllegalStateException("Mirror detection requires opponent data before recording samples");
        }
        if (!Double.isFinite(error) || error < 0.0) {
            throw new IllegalArgumentException("Mirror detection requires a finite non-negative error");
        }
        currentBattleSampleCount++;
        currentBattleTotalError += error;
        currentBattleTotalSquaredError += error * error;
    }

    public static void markEnabledEver() {
        if (trackedOpponentName == null) {
            throw new IllegalStateException("Mirror detection requires opponent data before enabling");
        }
        currentBattleEnabledEver = true;
    }

    public static boolean wasPerfectPredictionEverUnlocked() {
        return persistedEnabledEver || currentBattleEnabledEver;
    }

    public static int getCombinedSampleCount() {
        return persistedSampleCount + currentBattleSampleCount;
    }

    public static double getCombinedMeanError() {
        int combinedSampleCount = getCombinedSampleCount();
        if (combinedSampleCount <= 0) {
            return Double.NaN;
        }
        return (persistedTotalError + currentBattleTotalError) / combinedSampleCount;
    }

    public static double getCombinedMeanErrorUpperBound(double confidenceScale) {
        if (!(confidenceScale >= 0.0) || !Double.isFinite(confidenceScale)) {
            throw new IllegalArgumentException("Mirror detection requires a finite non-negative confidence scale");
        }
        double meanError = getCombinedMeanError();
        double standardError = getCombinedMeanErrorStandardError();
        if (Double.isNaN(meanError) || Double.isNaN(standardError)) {
            return Double.NaN;
        }
        if (standardError == 0.0) {
            return meanError;
        }
        return meanError + confidenceScale * standardError;
    }

    public static double getCombinedMeanErrorLowerBound(double confidenceScale) {
        if (!(confidenceScale >= 0.0) || !Double.isFinite(confidenceScale)) {
            throw new IllegalArgumentException("Mirror detection requires a finite non-negative confidence scale");
        }
        double meanError = getCombinedMeanError();
        double standardError = getCombinedMeanErrorStandardError();
        if (Double.isNaN(meanError) || Double.isNaN(standardError)) {
            return Double.NaN;
        }
        if (standardError == 0.0) {
            return meanError;
        }
        return Math.max(0.0, meanError - confidenceScale * standardError);
    }

    public static boolean isPerfectPredictionUnlocked(int minSampleCount,
                                                      double maxMeanError,
                                                      double confidenceScale) {
        if (minSampleCount < 0) {
            throw new IllegalArgumentException("Mirror detection requires a non-negative min sample count");
        }
        if (!(maxMeanError >= 0.0) || !Double.isFinite(maxMeanError)) {
            throw new IllegalArgumentException("Mirror detection requires a finite non-negative max mean error");
        }
        if (getCombinedSampleCount() < minSampleCount) {
            return false;
        }
        if (!wasPerfectPredictionEverUnlocked()) {
            double meanErrorUpperBound = getCombinedMeanErrorUpperBound(confidenceScale);
            return !Double.isNaN(meanErrorUpperBound) && meanErrorUpperBound <= maxMeanError;
        }
        double meanErrorLowerBound = getCombinedMeanErrorLowerBound(confidenceScale);
        return !Double.isNaN(meanErrorLowerBound) && meanErrorLowerBound <= maxMeanError;
    }

    private static double getCombinedMeanErrorStandardError() {
        int combinedSampleCount = getCombinedSampleCount();
        if (combinedSampleCount <= 0) {
            return Double.NaN;
        }
        if (combinedSampleCount == 1) {
            return 0.0;
        }
        double totalError = persistedTotalError + currentBattleTotalError;
        double totalSquaredError = persistedTotalSquaredError + currentBattleTotalSquaredError;
        double numerator =
                totalSquaredError - (totalError * totalError) / combinedSampleCount;
        double sampleVariance = Math.max(0.0, numerator / (combinedSampleCount - 1));
        return Math.sqrt(sampleVariance / combinedSampleCount);
    }

    public static boolean hasPersistedSectionData() {
        return trackedOpponentName != null
                && (persistedEnabledEver
                || currentBattleEnabledEver
                || persistedSampleCount > 0
                || currentBattleSampleCount > 0);
    }

    public static boolean isPersistedMirrorDetectionLoaded() {
        return persistedMirrorDetectionLoaded;
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes, boolean includeCurrentBattleData) {
        if (maxPayloadBytes < SECTION_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save mirror-detection payload: payload budget="
                            + maxPayloadBytes + " bytes");
        }
        int sampleCount = includeCurrentBattleData
                ? persistedSampleCount + currentBattleSampleCount
                : persistedSampleCount;
        double totalError = includeCurrentBattleData
                ? persistedTotalError + currentBattleTotalError
                : persistedTotalError;
        double totalSquaredError = includeCurrentBattleData
                ? persistedTotalSquaredError + currentBattleTotalSquaredError
                : persistedTotalSquaredError;
        boolean enabledEver = includeCurrentBattleData
                ? persistedEnabledEver || currentBattleEnabledEver
                : persistedEnabledEver;
        if (!enabledEver && sampleCount == 0) {
            return null;
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeBoolean(enabledEver);
            out.writeInt(sampleCount);
            out.writeDouble(totalError);
            out.writeDouble(totalSquaredError);
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length != SECTION_BYTES) {
                throw new IllegalStateException("Unexpected mirror-detection payload length");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build mirror-detection payload", e);
        }
    }

    private static void loadPersistedPayload(int sectionVersion, byte[] payload) {
        if (sectionVersion != SECTION_VERSION) {
            throw new IllegalStateException("Unsupported mirror-detection section version " + sectionVersion);
        }
        if (payload.length != SECTION_BYTES) {
            throw new IllegalStateException("Unexpected mirror-detection payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            boolean enabledEver = in.readBoolean();
            int sampleCount = in.readInt();
            double totalError = in.readDouble();
            double totalSquaredError = in.readDouble();
            if (sampleCount < 0) {
                throw new IllegalStateException("Mirror-detection sample count must be non-negative");
            }
            if (!Double.isFinite(totalError) || totalError < 0.0) {
                throw new IllegalStateException("Mirror-detection total error must be finite and non-negative");
            }
            if (!Double.isFinite(totalSquaredError) || totalSquaredError < 0.0) {
                throw new IllegalStateException("Mirror-detection squared error must be finite and non-negative");
            }
            if (totalSquaredError + 1e-9 < totalError * totalError / Math.max(1, sampleCount)) {
                throw new IllegalStateException("Mirror-detection squared error is inconsistent with total error");
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Mirror-detection payload contained trailing bytes");
            }
            persistedEnabledEver = enabledEver;
            persistedSampleCount = sampleCount;
            persistedTotalError = totalError;
            persistedTotalSquaredError = totalSquaredError;
            persistedMirrorDetectionLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable mirror-detection payload", e);
        }
    }
}
