package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.state.BulletPowerHitRateTracker;

public final class BulletPowerHitRateDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "scoremax-hit-rate";
    private static final int SECTION_MASK_BIT = 0x20;

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
        return BulletPowerHitRateTracker.persistenceSectionVersion();
    }

    @Override
    public void startBattle() {
        BulletPowerHitRateTracker.startBattlePersistence();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        BulletPowerHitRateTracker.ensureOpponentBaselineLoaded(opponentName, sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return BulletPowerHitRateTracker.hasPersistedSectionData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return BulletPowerHitRateTracker.createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData);
    }
}
