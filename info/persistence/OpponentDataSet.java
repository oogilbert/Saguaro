package oog.mega.saguaro.info.persistence;

import oog.mega.saguaro.Saguaro;

public interface OpponentDataSet {
    String key();

    int sectionMaskBit();

    int sectionVersion();

    void startBattle();

    void loadForOpponent(Saguaro robot, String opponentName, int sectionVersion, byte[] payload);

    boolean hasData();

    byte[] savePayload(Saguaro robot, String opponentName, int maxPayloadBytes, boolean includeCurrentBattleData);
}
