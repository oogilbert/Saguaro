package oog.mega.saguaro.mode.perfectprediction;

public enum ReactivePredictorId {
    ANTI_MIRROR("antiMirror"),
    ANTI_MIRROR_PERPENDICULAR("antiMirrorPerpendicular"),
    ANTI_MIRROR_COPY("antiMirrorCopy");

    private final String displayName;

    ReactivePredictorId(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
