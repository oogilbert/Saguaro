package oog.mega.saguaro.mode.perfectprediction;

public enum ReactivePredictorId {
    ANTI_MIRROR("antiMirror"),
    ANTI_MIRROR_PERPENDICULAR("antiMirrorPerpendicular"),
    ANTI_MIRROR_COPY("antiMirrorCopy");

    private final String displayName;

    ReactivePredictorId(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            throw new IllegalArgumentException("Reactive predictor ids require a non-empty display name");
        }
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
