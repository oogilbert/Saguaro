package oog.mega.saguaro.mode;

public enum ModeId {
    SCORE_MAX("scoreMax"),
    BULLET_SHIELD("bulletShield"),
    PERFECT_PREDICTION("perfectPrediction");

    private final String label;

    ModeId(String label) {
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("Mode label must be non-empty");
        }
        this.label = label;
    }

    public String label() {
        return label;
    }
}
