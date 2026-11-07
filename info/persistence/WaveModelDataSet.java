package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.rule.RuleProfile;

/** The opponent model's record: what the rule models learned about this opponent. */
public final class WaveModelDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "wave-model";
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
        return RuleProfile.PERSISTENCE_VERSION;
    }

    @Override
    public void startBattle() {
        RuleProfile.INSTANCE.startBattlePersistence();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        RuleProfile.INSTANCE.loadPersisted(sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return RuleProfile.INSTANCE.hasPersistedData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return RuleProfile.INSTANCE.persistedPayload(maxPayloadBytes);
    }
}
