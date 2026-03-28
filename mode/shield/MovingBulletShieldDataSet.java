package oog.mega.saguaro.mode.shield;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.persistence.OpponentDataSet;
import oog.mega.saguaro.mode.ModeId;

public final class MovingBulletShieldDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "moving-shield-bootstrap";
    private static final int SECTION_MASK_BIT = 0x80;
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
        BulletShieldMode.startBattlePersistence(ModeId.MOVING_BULLET_SHIELD);
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        BulletShieldMode.loadPersistedBootstrap(ModeId.MOVING_BULLET_SHIELD, sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return BulletShieldMode.hasPersistedBootstrapData(ModeId.MOVING_BULLET_SHIELD);
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return BulletShieldMode.createPersistedBootstrapPayload(
                ModeId.MOVING_BULLET_SHIELD,
                maxPayloadBytes,
                includeCurrentBattleData);
    }
}
