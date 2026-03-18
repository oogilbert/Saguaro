package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.mode.ModePerformanceProfile;

public final class ModePerformanceDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "mode-performance";
    private static final int SECTION_MASK_BIT = 0x08;

    @Override
    public String key() {
        return DATA_SET_KEY;
    }

    @Override
    public int sectionMaskBit() {
        return SECTION_MASK_BIT;
    }

    @Override
    public int sectionVersion() {
        return ModePerformanceProfile.persistenceSectionVersion();
    }

    @Override
    public void startBattle() {
        ModePerformanceProfile.startBattle();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        ModePerformanceProfile.ensureOpponentBaselineLoaded(opponentName, sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return ModePerformanceProfile.hasPersistedSectionData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return ModePerformanceProfile.createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData);
    }
}
