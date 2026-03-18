package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.wave.WaveLog;

public final class WaveLogModelDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "wave-log-model";
    private static final int SECTION_MASK_BIT = 0x40;

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
        return WaveLog.persistenceSectionVersion();
    }

    @Override
    public void startBattle() {
        WaveLog.startBattlePersistence();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        WaveLog.ensureOpponentBaselineLoaded(opponentName, sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return WaveLog.hasPersistedSectionData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return WaveLog.createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData);
    }
}
