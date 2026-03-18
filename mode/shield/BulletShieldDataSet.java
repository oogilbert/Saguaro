package oog.mega.saguaro.mode.shield;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.persistence.OpponentDataSet;

public final class BulletShieldDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "shield-bootstrap";
    private static final int SECTION_MASK_BIT = 0x04;
    private static final int SECTION_VERSION = 1;

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
        return SECTION_VERSION;
    }

    @Override
    public void startBattle() {
        BulletShieldMode.startBattlePersistence();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        BulletShieldMode.loadPersistedBootstrap(sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return BulletShieldMode.hasPersistedBootstrapData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return BulletShieldMode.createPersistedBootstrapPayload(maxPayloadBytes, includeCurrentBattleData);
    }
}
