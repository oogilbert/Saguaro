package oog.mega.saguaro.mode;

public enum ModeId {
    SCORE_MAX("scoreMax", "ScoreMax"),
    BULLET_SHIELD("bulletShield", "BulletShielding"),
    MOVING_BULLET_SHIELD("movingBulletShield", "MovingBulletShielding"),
    PERFECT_PREDICTION("perfectPrediction", "PerfectPrediction"),
    SHOT_DODGER("shotDodger", "ShotDodger"),
    WAVE_POISON("wavePoison", "WavePoison");

    private final String label;
    private final String displayName;

    ModeId(String label, String displayName) {
        this.label = label;
        this.displayName = displayName;
    }

    public String label() {
        return label;
    }

    public String displayName() {
        return displayName;
    }
}
