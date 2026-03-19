package oog.mega.saguaro.mode;

public enum ModeId {
    SCORE_MAX(0, "scoreMax"),
    BULLET_SHIELD(1, "bulletShield"),
    PERFECT_PREDICTION(2, "perfectPrediction"),
    GTO(3, "gto");

    private static final ModeId[] PERSISTENCE_ORDER = buildPersistenceOrder();

    private final int persistenceIndex;
    private final String label;

    ModeId(int persistenceIndex, String label) {
        if (persistenceIndex < 0) {
            throw new IllegalArgumentException("Mode persistence index must be non-negative");
        }
        if (label == null || label.isEmpty()) {
            throw new IllegalArgumentException("Mode label must be non-empty");
        }
        this.persistenceIndex = persistenceIndex;
        this.label = label;
    }

    public int persistenceIndex() {
        return persistenceIndex;
    }

    public String label() {
        return label;
    }

    public static ModeId fromPersistenceIndex(int persistenceIndex) {
        return persistenceIndex >= 0 && persistenceIndex < PERSISTENCE_ORDER.length
                ? PERSISTENCE_ORDER[persistenceIndex]
                : null;
    }

    public static ModeId[] orderedByPersistenceIndex() {
        return PERSISTENCE_ORDER.clone();
    }

    private static ModeId[] buildPersistenceOrder() {
        ModeId[] modes = values();
        ModeId[] ordered = new ModeId[modes.length];
        for (ModeId modeId : modes) {
            if (modeId.persistenceIndex >= ordered.length) {
                throw new IllegalStateException("Mode persistence indices must be contiguous and zero-based");
            }
            if (ordered[modeId.persistenceIndex] != null) {
                throw new IllegalStateException("Duplicate mode persistence index " + modeId.persistenceIndex);
            }
            ordered[modeId.persistenceIndex] = modeId;
        }
        return ordered;
    }
}
