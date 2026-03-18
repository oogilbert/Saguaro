package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.ScoreMaxScoreHistoryProfile;

public final class ScoreHistoryDataSet implements OpponentDataSet {
    static final String DATA_SET_KEY = "score-history";
    private static final int SECTION_MASK_BIT = 0x01;

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
        return ScoreMaxScoreHistoryProfile.persistenceSectionVersion();
    }

    @Override
    public void startBattle() {
        ScoreMaxScoreHistoryProfile.INSTANCE.startBattle();
    }

    @Override
    public void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload) {
        ScoreMaxScoreHistoryProfile.ensureOpponentBaselineLoaded(opponentName, sectionVersion, payload);
    }

    @Override
    public boolean hasData() {
        return ScoreMaxScoreHistoryProfile.hasPersistedSectionData();
    }

    @Override
    public byte[] savePayload(Saguaro robot,
                              String opponentName,
                              int maxPayloadBytes,
                              boolean includeCurrentBattleData) {
        return ScoreMaxScoreHistoryProfile.createPersistedSectionPayload(maxPayloadBytes, includeCurrentBattleData);
    }
}
