package oog.mega.saguaro.mode.shotdodger;

enum ShotDodgerExpertId {
    STATE_CONTINUATION,
    MINI_PATTERN,
    ALTERNATING_MINI_PATTERN,
    STOP,
    ESCAPE_AHEAD,
    ESCAPE_REVERSE,
    LAST_DISPLACEMENT,
    REVERSE_LAST_DISPLACEMENT,
    HALF_AHEAD,
    HALF_REVERSE,
    CENTER_OF_MASS,
    COSTANZA;

    static final ShotDodgerExpertId[] VALUES = values();
}
