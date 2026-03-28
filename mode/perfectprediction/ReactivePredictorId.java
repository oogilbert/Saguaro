package oog.mega.saguaro.mode.perfectprediction;

public enum ReactivePredictorId {
    LEARNED_PATTERN("learnedPattern"),
    ANTI_MIRROR("antiMirror"),
    ANTI_MIRROR_PERPENDICULAR("antiMirrorPerpendicular"),
    ANTI_MIRROR_COPY("antiMirrorCopy"),
    RAM_DIRECT("ramDirect"),
    RAM_DIRECT_BACK_AS_FRONT("ramDirectBackAsFront"),
    RAM_LINEAR("ramLinear"),
    RAM_LINEAR_BACK_AS_FRONT("ramLinearBackAsFront");

    private final String displayName;

    ReactivePredictorId(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
