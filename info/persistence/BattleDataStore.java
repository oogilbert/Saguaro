package oog.mega.saguaro.info.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import robocode.RobocodeFileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.state.BulletPowerHitRateTracker;
import oog.mega.saguaro.info.wave.WaveLog;
import oog.mega.saguaro.mode.ModeController;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.mode.ModePerformanceProfile;
import oog.mega.saguaro.mode.shield.BulletShieldDataSet;
import oog.mega.saguaro.mode.shield.BulletShieldMode;
import oog.mega.saguaro.mode.shield.MovingBulletShieldDataSet;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerDataSet;

public final class BattleDataStore {
    private static final int CONTAINER_MAGIC = 0x50445354; // "PDST"
    private static final int CONTAINER_VERSION = 1;
    private static final int CONTAINER_HEADER_BYTES = 6;
    private static final int SECTION_HEADER_BYTES = 3;
    private static final int MAX_OPPONENT_RECORD_BYTES = 167;
    private final List<OpponentDataSet> registeredDataSets = new ArrayList<>();
    private final Map<Class<? extends OpponentDataSet>, OpponentDataSet> dataSetsByType = new LinkedHashMap<>();
    private final Map<String, OpponentDataSet> dataSetsByKey = new LinkedHashMap<>();
    private final Set<Class<? extends OpponentDataSet>> requestedSaveTypes = new LinkedHashSet<>();
    private String trackedOpponentName;
    private boolean battleStarted;
    private boolean baselineStatusReportedThisBattle;
    private boolean suppressSavesForBattle;
    private boolean suppressSaveStatusReportedThisBattle;
    private String suppressSaveReason;
    private boolean trackedOpponentHadPersistedData;
    private ModeId persistedLockedModeId;

    public BattleDataStore() {
    }

    public void startBattle() {
        battleStarted = true;
        trackedOpponentName = null;
        requestedSaveTypes.clear();
        baselineStatusReportedThisBattle = false;
        suppressSavesForBattle = false;
        suppressSaveStatusReportedThisBattle = false;
        suppressSaveReason = null;
        trackedOpponentHadPersistedData = false;
        persistedLockedModeId = null;
        for (OpponentDataSet dataSet : registeredDataSets) {
            dataSet.startBattle();
        }
    }

    public void ensureOpponentDataLoaded(Saguaro robot, String opponentName) {
        if (robot == null) {
            throw new IllegalArgumentException("Battle data store requires a robot instance");
        }
        if (opponentName == null || opponentName.isEmpty()) {
            throw new IllegalArgumentException("Battle data store requires a non-empty opponent name");
        }
        if (trackedOpponentName == null) {
            trackedOpponentName = opponentName;
            File dataFile = recordFile(robot, opponentName);
            Map<Integer, StoredSection> sections = loadSections(robot, opponentName, dataFile);
            try {
                loadRegisteredDataSets(robot, opponentName, sections);
                trackedOpponentHadPersistedData = !sections.isEmpty();
                persistedLockedModeId = ModePerformanceProfile.getPersistedLockedMode();
            } catch (RuntimeException e) {
                if (!discardStaleRecord(robot, dataFile, opponentName, "invalid section payload")) {
                    suppressSavesForBattle(
                            "persisted baseline could not be invalidated after an invalid section payload");
                }
                resetRegisteredDataSetsForFallback();
                loadRegisteredDataSets(robot, opponentName, new LinkedHashMap<Integer, StoredSection>());
                trackedOpponentHadPersistedData = false;
                persistedLockedModeId = ModePerformanceProfile.getPersistedLockedMode();
            }
            reportBaselineStatus(robot);
            return;
        }
        if (!trackedOpponentName.equals(opponentName)) {
            throw new IllegalStateException(
                    "Battle data store expected a single opponent but saw: "
                            + trackedOpponentName + " and " + opponentName);
        }
    }

    public void requestDataSetSave(Class<? extends OpponentDataSet> dataSetType) {
        if (dataSetType == null) {
            throw new IllegalArgumentException("Battle data store requires a non-null dataset type");
        }
        getDataSet(dataSetType);
        requestedSaveTypes.add(dataSetType);
    }

    public void saveRequestedData(Saguaro robot) {
        if (robot == null) {
            throw new IllegalArgumentException("Battle data store requires a robot instance to save data");
        }
        writeCurrentBattleRecord(robot);
    }

    public <T extends OpponentDataSet> T getDataSet(Class<T> dataSetType) {
        if (dataSetType == null) {
            throw new IllegalArgumentException("Battle data store requires a non-null dataset type");
        }
        OpponentDataSet dataSet = dataSetsByType.get(dataSetType);
        if (dataSet == null) {
            throw new IllegalArgumentException("No dataset registered for type: " + dataSetType.getName());
        }
        return dataSetType.cast(dataSet);
    }

    public <T extends OpponentDataSet> T registerDataSet(T dataSet) {
        if (dataSet == null) {
            throw new IllegalArgumentException("Battle data store requires non-null datasets");
        }
        @SuppressWarnings("unchecked")
        Class<T> dataSetType = (Class<T>) dataSet.getClass();
        OpponentDataSet existing = dataSetsByType.get(dataSetType);
        if (existing != null) {
            return dataSetType.cast(existing);
        }
        String key = dataSet.key();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Battle data store requires datasets to declare a stable key");
        }
        int sectionMaskBit = dataSet.sectionMaskBit();
        if (sectionMaskBit <= 0 || sectionMaskBit > 0x80 || Integer.bitCount(sectionMaskBit) != 1) {
            throw new IllegalArgumentException("Datasets must declare a single section bit in the low byte");
        }
        if (trackedOpponentName != null) {
            throw new IllegalStateException(
                    "Cannot register dataset after opponent data has already been loaded: " + dataSetType.getName());
        }
        if (dataSetsByKey.containsKey(key)) {
            throw new IllegalStateException("Duplicate dataset key registered: " + key);
        }
        for (OpponentDataSet registered : registeredDataSets) {
            if (registered.sectionMaskBit() == sectionMaskBit) {
                throw new IllegalStateException("Duplicate dataset section bit registered: " + sectionMaskBit);
            }
        }
        registeredDataSets.add(dataSet);
        dataSetsByType.put(dataSetType, dataSet);
        dataSetsByKey.put(key, dataSet);
        if (battleStarted) {
            dataSet.startBattle();
        }
        return dataSet;
    }

    public boolean isFirstBattleForTrackedOpponent() {
        return trackedOpponentName != null && !trackedOpponentHadPersistedData;
    }

    public ModeId getPersistedLockedMode() {
        return persistedLockedModeId;
    }

    private void writeCurrentBattleRecord(Saguaro robot) {
        if (trackedOpponentName == null || requestedSaveTypes.isEmpty()) {
            return;
        }
        if (suppressSavesForBattle) {
            reportSuppressedSaveStatus(robot);
            requestedSaveTypes.clear();
            return;
        }
        File dataFile = recordFile(robot, trackedOpponentName);
        long existingBytes = dataFile.isFile() ? dataFile.length() : 0L;
        long writableBytes = robot.getDataQuotaAvailable() + existingBytes;
        if (writableBytes < CONTAINER_HEADER_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save opponent record for " + trackedOpponentName
                            + ": available=" + writableBytes + " bytes");
        }

        int globalBudget = (int) Math.min(Integer.MAX_VALUE, writableBytes);
        PersistedModeDataPlan dataPlan = buildPersistedModeDataPlan();

        Map<Integer, byte[]> builtPayloads = new LinkedHashMap<>();
        int totalSize = CONTAINER_HEADER_BYTES;
        for (OpponentDataSet dataSet : registeredDataSets) {
            if (!dataPlan.includesDataSet(dataSet)) {
                continue;
            }
            boolean includeCurrentBattleData = requestedSaveTypes.contains(dataSet.getClass());
            int maxPayloadBytes = globalBudget - totalSize - SECTION_HEADER_BYTES;
            if (maxPayloadBytes <= 0) {
                continue;
            }
            byte[] sectionPayload = buildSectionPayload(
                    robot,
                    dataSet,
                    maxPayloadBytes,
                    includeCurrentBattleData,
                    dataPlan);
            if (sectionPayload == null || sectionPayload.length == 0) {
                continue;
            }
            if (sectionPayload.length > 0xffff) {
                throw new IllegalStateException("Section payload too large for dataset " + dataSet.key());
            }
            builtPayloads.put(dataSet.sectionMaskBit(), sectionPayload);
            totalSize += SECTION_HEADER_BYTES + sectionPayload.length;
        }
        if (totalSize > MAX_OPPONENT_RECORD_BYTES) {
            robot.out.println(
                    "WARNING: data file exceeded opponent budget for " + trackedOpponentName
                            + " (size=" + totalSize + " bytes, budget=" + MAX_OPPONENT_RECORD_BYTES + " bytes)");
        }

        // Assemble output buffer in registration order.
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream();
        int sectionMask = 0;
        for (OpponentDataSet dataSet : registeredDataSets) {
            byte[] sectionPayload = builtPayloads.get(dataSet.sectionMaskBit());
            if (sectionPayload == null) {
                continue;
            }
            try (DataOutputStream out = new DataOutputStream(payloadBuffer)) {
                out.writeByte(dataSet.sectionVersion());
                out.writeShort(sectionPayload.length);
                out.write(sectionPayload);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to build opponent record for " + trackedOpponentName, e);
            }
            sectionMask |= dataSet.sectionMaskBit();
        }

        if (sectionMask == 0) {
            requestedSaveTypes.clear();
            return;
        }

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new RobocodeFileOutputStream(dataFile.getPath(), false)))) {
            out.writeInt(CONTAINER_MAGIC);
            out.writeByte(CONTAINER_VERSION);
            out.writeByte(sectionMask);
            payloadBuffer.writeTo(out);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save opponent record for " + trackedOpponentName, e);
        } finally {
            requestedSaveTypes.clear();
        }
    }

    private void resetRegisteredDataSetsForFallback() {
        requestedSaveTypes.clear();
        for (OpponentDataSet dataSet : registeredDataSets) {
            dataSet.startBattle();
        }
    }

    private void loadRegisteredDataSets(Saguaro robot,
                                        String opponentName,
                                        Map<Integer, StoredSection> sections) {
        for (OpponentDataSet dataSet : registeredDataSets) {
            StoredSection section = sections.get(dataSet.sectionMaskBit());
            dataSet.loadForOpponent(
                    robot,
                    opponentName,
                    section != null ? section.version : 0,
                    section != null ? section.payload : null);
        }
    }

    private void reportBaselineStatus(Saguaro robot) {
        if (baselineStatusReportedThisBattle) {
            return;
        }
        robot.out.println(hasAnyOpponentDataLoaded() ? "Opponent data loaded" : "No opponent data loaded");
        if (persistedLockedModeId != null) {
            robot.out.println("Persisted mode lock: " + persistedLockedModeId.displayName());
            robot.out.println(
                    "Locked mode estimate: " + ModeController.describeModeEstimate(persistedLockedModeId));
        } else {
            robot.out.println(
                    "BulletShielding mode estimate: " + ModeController.describeModeEstimate(ModeId.BULLET_SHIELD));
            robot.out.println(
                    "MovingBulletShielding mode estimate: "
                            + ModeController.describeModeEstimate(ModeId.MOVING_BULLET_SHIELD));
            robot.out.println(
                    "PerfectPrediction mode estimate: "
                            + ModeController.describeModeEstimate(ModeId.PERFECT_PREDICTION));
            robot.out.println("ScoreMax mode estimate: " + ModeController.describeModeEstimate(ModeId.SCORE_MAX));
            robot.out.println("ShotDodger mode estimate: " + ModeController.describeModeEstimate(ModeId.SHOT_DODGER));
            robot.out.println("WavePoison mode estimate: " + ModeController.describeModeEstimate(ModeId.WAVE_POISON));
            robot.out.println(
                    "WavePoisonShift mode estimate: "
                            + ModeController.describeModeEstimate(ModeId.WAVE_POISON_SHIFT));
            robot.out.println(
                    "AntiBasicSurfer mode estimate: "
                            + ModeController.describeModeEstimate(ModeId.ANTI_BASIC_SURFER));
        }
        double historicalHitRate = BulletPowerHitRateTracker.getPersistedOverallHitRate();
        if (!Double.isNaN(historicalHitRate)) {
            robot.out.println(String.format(Locale.US, "Historical hit rate: %.2f%%", historicalHitRate * 100.0));
        }
        reportSuppressedSaveStatus(robot);
        baselineStatusReportedThisBattle = true;
    }

    private void suppressSavesForBattle(String reason) {
        if (suppressSavesForBattle) {
            return;
        }
        suppressSavesForBattle = true;
        suppressSaveReason = reason;
    }

    private void reportSuppressedSaveStatus(Saguaro robot) {
        if (!suppressSavesForBattle || suppressSaveStatusReportedThisBattle || robot == null) {
            return;
        }
        robot.out.println(
                "Persisted baseline save disabled for this battle: "
                        + (suppressSaveReason != null ? suppressSaveReason : "unavailable baseline file"));
        suppressSaveStatusReportedThisBattle = true;
    }

    private static boolean hasAnyOpponentDataLoaded() {
        return ModePerformanceProfile.isPersistedModeStatsLoaded()
                || BulletShieldMode.isAnyPersistedBootstrapLoaded()
                || BulletPowerHitRateTracker.isPersistedBaselineLoaded()
                || WaveLog.hasPersistedSectionData();
    }

    private PersistedModeDataPlan buildPersistedModeDataPlan() {
        ModeId settledModeId = ModeController.findSettledModeForPersistence();
        if (settledModeId == null) {
            return PersistedModeDataPlan.unrestricted();
        }
        ModePerformanceProfile.ModeStatsSnapshot settledStats = ModePerformanceProfile.getCombinedStats(settledModeId);
        double totalScore = settledStats.totalOurScore + settledStats.totalOpponentScore;
        if (totalScore + 1e-9 < BotConfig.ModeSelection.MIN_SETTLED_MODE_TOTAL_SCORE) {
            return PersistedModeDataPlan.unrestricted();
        }
        return PersistedModeDataPlan.locked(settledModeId);
    }

    private byte[] buildSectionPayload(Saguaro robot,
                                       OpponentDataSet dataSet,
                                       int maxPayloadBytes,
                                       boolean includeCurrentBattleData,
                                       PersistedModeDataPlan dataPlan) {
        if (dataSet instanceof ModePerformanceDataSet) {
            return ModePerformanceProfile.createPersistedSectionPayload(
                    maxPayloadBytes,
                    includeCurrentBattleData,
                    dataPlan.lockedModeId);
        }
        if (dataSet instanceof WaveModelDataSet) {
            return WaveLog.createPersistedSectionPayload(
                    maxPayloadBytes,
                    includeCurrentBattleData,
                    dataPlan.includeWaveTargetingModel,
                    dataPlan.includeWaveMovementModel);
        }
        return dataSet.savePayload(robot, trackedOpponentName, maxPayloadBytes, includeCurrentBattleData);
    }

    private Map<Integer, StoredSection> loadSections(Saguaro robot, String opponentName, File dataFile) {
        if (!dataFile.isFile() || dataFile.length() == 0L) {
            return new LinkedHashMap<>();
        }

        String staleReason = null;
        Map<Integer, StoredSection> sections = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(dataFile)))) {
            int magic = in.readInt();
            if (magic != CONTAINER_MAGIC) {
                staleReason = "legacy or incompatible record format";
            }
            if (staleReason == null) {
                int version = in.readUnsignedByte();
                if (version != CONTAINER_VERSION) {
                    staleReason = "unsupported container version " + version;
                }
            }
            int sectionMask = 0;
            if (staleReason == null) {
                sectionMask = in.readUnsignedByte();
                int knownMask = combinedKnownSectionMask();
                if ((sectionMask & ~knownMask) != 0) {
                    staleReason = "record contains unknown sections";
                }
            }
            if (staleReason == null) {
                // Sections are read in registeredDataSets order, which must match the write order
                // in writeCurrentBattleRecord. The section mask indicates which sections are
                // present but does not encode their position — if registration order ever diverges
                // between write and read (e.g. after a refactor), sections will be read as the
                // wrong dataset.
                for (OpponentDataSet dataSet : registeredDataSets) {
                    if ((sectionMask & dataSet.sectionMaskBit()) == 0) {
                        continue;
                    }
                    int sectionVersion = in.readUnsignedByte();
                    int sectionLength = in.readUnsignedShort();
                    byte[] payload = new byte[sectionLength];
                    in.readFully(payload);
                    sections.put(dataSet.sectionMaskBit(), new StoredSection(sectionVersion, payload));
                }
                if (in.read() != -1) {
                    staleReason = "record contained trailing bytes";
                }
            }
        } catch (IOException e) {
            if (robot != null) {
                robot.out.println(
                        "Unable to read persisted baseline for "
                                + opponentName
                                + ": "
                                + describeIOException(e)
                                + ". Using defaults and leaving the file untouched.");
            }
            suppressSavesForBattle("persisted baseline could not be read safely");
            return new LinkedHashMap<>();
        }

        if (staleReason != null) {
            if (!discardStaleRecord(robot, dataFile, opponentName, staleReason)) {
                suppressSavesForBattle(
                        "persisted baseline could not be invalidated (" + staleReason + ")");
            }
            return new LinkedHashMap<>();
        }
        return sections;
    }



    private int combinedKnownSectionMask() {
        int mask = 0;
        for (OpponentDataSet dataSet : registeredDataSets) {
            mask |= dataSet.sectionMaskBit();
        }
        return mask;
    }

    private static boolean discardStaleRecord(Saguaro robot, File dataFile, String opponentName, String reason) {
        if (!dataFile.isFile()) {
            return true;
        }
        if (!dataFile.delete()) {
            if (robot != null) {
                robot.out.println(
                        "Unable to delete invalid persisted baseline for "
                                + opponentName
                                + ": "
                                + reason
                                + " ("
                                + dataFile.getName()
                                + "). Using defaults and leaving the file untouched.");
            }
            return false;
        }
        if (robot != null) {
            robot.out.println(
                    "Deleted invalid persisted baseline for " + opponentName
                            + ": " + reason
                            + " (" + dataFile.getName() + ")");
        }
        return true;
    }

    private static String describeIOException(IOException e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : e.getClass().getSimpleName();
    }

    private static File recordFile(Saguaro robot, String opponentName) {
        return robot.getDataFile(recordFileName(opponentName));
    }

    private static String recordFileName(String opponentName) {
        return "opponent-" + safeFileStem(stripDuplicateIndex(opponentName)) + ".dat";
    }

    private static String stripDuplicateIndex(String name) {
        if (name.endsWith(")")) {
            int openParen = name.lastIndexOf(" (");
            if (openParen >= 0) {
                boolean allDigits = openParen + 2 < name.length() - 1;
                for (int i = openParen + 2; i < name.length() - 1; i++) {
                    if (!Character.isDigit(name.charAt(i))) {
                        allDigits = false;
                        break;
                    }
                }
                if (allDigits) {
                    return name.substring(0, openParen);
                }
            }
        }
        return name;
    }

    private static String safeFileStem(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_' || ch == '-') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        builder.append('-').append(Integer.toHexString(value.hashCode()));
        return builder.toString();
    }

    private static final class StoredSection {
        final int version;
        final byte[] payload;

        StoredSection(int version, byte[] payload) {
            this.version = version;
            this.payload = payload;
        }
    }

    private static final class PersistedModeDataPlan {
        final ModeId lockedModeId;
        final boolean includeWaveTargetingModel;
        final boolean includeWaveMovementModel;
        final boolean includeBulletPowerHitRate;
        final boolean includeShotDodgerBootstrap;
        final boolean includeBulletShieldBootstrap;
        final boolean includeMovingBulletShieldBootstrap;

        private PersistedModeDataPlan(ModeId lockedModeId,
                                      boolean includeWaveTargetingModel,
                                      boolean includeWaveMovementModel,
                                      boolean includeBulletPowerHitRate,
                                      boolean includeShotDodgerBootstrap,
                                      boolean includeBulletShieldBootstrap,
                                      boolean includeMovingBulletShieldBootstrap) {
            this.lockedModeId = lockedModeId;
            this.includeWaveTargetingModel = includeWaveTargetingModel;
            this.includeWaveMovementModel = includeWaveMovementModel;
            this.includeBulletPowerHitRate = includeBulletPowerHitRate;
            this.includeShotDodgerBootstrap = includeShotDodgerBootstrap;
            this.includeBulletShieldBootstrap = includeBulletShieldBootstrap;
            this.includeMovingBulletShieldBootstrap = includeMovingBulletShieldBootstrap;
        }

        static PersistedModeDataPlan unrestricted() {
            return new PersistedModeDataPlan(null, true, true, true, true, true, true);
        }

        static PersistedModeDataPlan locked(ModeId modeId) {
            if (modeId == null) {
                throw new IllegalArgumentException("Locked persistence plan requires a non-null mode id");
            }
            if (modeId == ModeId.SCORE_MAX || modeId == ModeId.ANTI_BASIC_SURFER) {
                return new PersistedModeDataPlan(modeId, true, true, true, false, false, false);
            }
            if (modeId == ModeId.PERFECT_PREDICTION) {
                return new PersistedModeDataPlan(modeId, false, true, false, false, false, false);
            }
            if (modeId == ModeId.BULLET_SHIELD) {
                return new PersistedModeDataPlan(modeId, true, false, false, false, true, false);
            }
            if (modeId == ModeId.MOVING_BULLET_SHIELD) {
                return new PersistedModeDataPlan(modeId, true, false, false, false, false, true);
            }
            if (modeId == ModeId.SHOT_DODGER
                    || modeId == ModeId.WAVE_POISON
                    || modeId == ModeId.WAVE_POISON_SHIFT) {
                return new PersistedModeDataPlan(modeId, true, false, false, true, false, false);
            }
            throw new IllegalArgumentException("Unsupported locked persistence mode " + modeId);
        }

        boolean includesDataSet(OpponentDataSet dataSet) {
            if (dataSet == null) {
                return false;
            }
            if (dataSet instanceof ModePerformanceDataSet) {
                return true;
            }
            if (lockedModeId == null) {
                return true;
            }
            if (dataSet instanceof WaveModelDataSet) {
                return includeWaveTargetingModel || includeWaveMovementModel;
            }
            if (dataSet instanceof BulletPowerHitRateDataSet) {
                return includeBulletPowerHitRate;
            }
            if (dataSet instanceof ShotDodgerDataSet) {
                return includeShotDodgerBootstrap;
            }
            if (dataSet instanceof BulletShieldDataSet) {
                return includeBulletShieldBootstrap;
            }
            if (dataSet instanceof MovingBulletShieldDataSet) {
                return includeMovingBulletShieldBootstrap;
            }
            return false;
        }
    }
}
