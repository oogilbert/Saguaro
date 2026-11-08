package oog.mega.saguaro.mode;

public enum ModeId {
    SCORE_MAX("scoreMax", "ScoreMax",
            "A worthy challenger? Enabling ScoreMax mode."),
    BULLET_SHIELD("bulletShield", "BulletShielding",
            "Pew Pew Pew Pew! Enabling BulletShielding mode."),
    MOVING_BULLET_SHIELD("movingBulletShield", "MovingBulletShielding",
            "Wait for me to get a nice spot please. Enabling MovingBulletShielding mode"),
    PERFECT_PREDICTION("perfectPrediction", "PerfectPrediction",
            "I guess you don't really like to dodge bullets, huh? Enabling PerfectPrediction mode."),
    SHOT_DODGER("shotDodger", "ShotDodger",
            "You won't hit me a single time! Enabling ShotDodger mode"),
    WAVE_POISON("wavePoison", "WavePoison",
            "Where are you shooting, silly? Enabling WavePoison mode"),
    WAVE_POISON_SHIFT("wavePoisonShift", "WavePoisonShift",
            "Where are you shooting, silly? Enabling WavePoisonShift mode."),
    ANTI_BASIC_SURFER("antiBasicSurfer", "AntiBasicSurfer",
            "I'm sorry, my child. Enabling AntiBasicSurfer mode.");

    private final String label;
    private final String displayName;
    private final String taunt;

    ModeId(String label, String displayName, String taunt) {
        this.label = label;
        this.displayName = displayName;
        this.taunt = taunt;
    }

    /** What Saguaro says to the opponent when it takes up this mode. */
    public String taunt() {
        return taunt;
    }

    public String label() {
        return label;
    }

    public String displayName() {
        return displayName;
    }
}
