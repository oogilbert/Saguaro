package oog.mega.saguaro.mode.shotdodger;

enum ShotDodgerExpertId {
    HEAD_ON,
    HEAD_ON_NO_GUN_ADJUST(HEAD_ON, true);

    static final ShotDodgerExpertId[] VALUES = values();

    private final ShotDodgerExpertId sourceExpertId;
    private final boolean applyFireTimeBodyTurn;

    ShotDodgerExpertId() {
        this(null, false);
    }

    ShotDodgerExpertId(ShotDodgerExpertId sourceExpertId,
                       boolean applyFireTimeBodyTurn) {
        this.sourceExpertId = sourceExpertId;
        this.applyFireTimeBodyTurn = applyFireTimeBodyTurn;
    }

    ShotDodgerExpertId sourceExpertId() {
        return sourceExpertId;
    }

    boolean applyFireTimeBodyTurn() {
        return applyFireTimeBodyTurn;
    }
}
