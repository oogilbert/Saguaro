package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.MirrorDetectionProfile;

public final class MirrorDetectionDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "mirror-detection";
    private static final int SECTION_MASK_BIT = 0x10;

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
        return MirrorDetectionProfile.persistenceSectionVersion();
    }

    @Override
    public void startBattle() {
        MirrorDetectionProfile.startBattle();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        MirrorDetectionProfile.ensureOpponentBaselineLoaded(opponentName, sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return MirrorDetectionProfile.hasPersistedSectionData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return MirrorDetectionProfile.createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData);
    }
}
