package oog.mega.saguaro.mode.shotdodger;

enum ShotDodgerExpertId {
    HEAD_ON,
    LINEAR,
    LINEAR_CONSTANT_DIVISOR,
    CIRCULAR,
    HEAD_ON_NO_GUN_ADJUST(HEAD_ON, true),
    LINEAR_NO_GUN_ADJUST(LINEAR, true),
    LINEAR_CONSTANT_DIVISOR_NO_GUN_ADJUST(true),
    CIRCULAR_NO_GUN_ADJUST(CIRCULAR, true);

    static final ShotDodgerExpertId[] VALUES = values();

    private final ShotDodgerExpertId sourceExpertId;
    private final boolean applyFireTimeBodyTurn;

    ShotDodgerExpertId() {
        this(null, false);
    }

    ShotDodgerExpertId(boolean applyFireTimeBodyTurn) {
        this(null, applyFireTimeBodyTurn);
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
