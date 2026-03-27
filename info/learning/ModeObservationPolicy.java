package oog.mega.saguaro.info.learning;

public final class ModeObservationPolicy {
    public static final ModeObservationPolicy FULL =
            new ModeObservationPolicy(true, true, true, true, true, true, true, true, false);
    public static final ModeObservationPolicy NONE =
            new ModeObservationPolicy(false, false, false, false, false, false, false, false, false);
    public static final ModeObservationPolicy TARGETING_ONLY =
            new ModeObservationPolicy(true, true, false, false, false, false, false, false, true);
    public static final ModeObservationPolicy COLLECT_ONLY =
            new ModeObservationPolicy(false, true, true, true, true, true, false, false, false);
    public static final ModeObservationPolicy SHOT_DODGER_EXPERT_ONLY =
            new ModeObservationPolicy(true, true, true, true, false, false, true, false, false);

    public final boolean useTargetingDistributions;
    public final boolean logTargetingObservations;
    public final boolean saveTargetingObservations;
    public final boolean useMovementDistributions;
    public final boolean logMovementObservations;
    public final boolean saveMovementObservations;
    public final boolean updateTargetingModel;
    public final boolean updateMovementModel;
    public final boolean preserveMyWaveAfterBulletCollision;

    public ModeObservationPolicy(boolean useTargetingDistributions,
                                 boolean logTargetingObservations,
                                 boolean saveTargetingObservations,
                                 boolean useMovementDistributions,
                                 boolean logMovementObservations,
                                 boolean saveMovementObservations,
                                 boolean updateTargetingModel,
                                 boolean updateMovementModel,
                                 boolean preserveMyWaveAfterBulletCollision) {
        this.useTargetingDistributions = useTargetingDistributions;
        this.logTargetingObservations = logTargetingObservations;
        this.saveTargetingObservations = saveTargetingObservations;
        this.useMovementDistributions = useMovementDistributions;
        this.logMovementObservations = logMovementObservations;
        this.saveMovementObservations = saveMovementObservations;
        this.updateTargetingModel = updateTargetingModel;
        this.updateMovementModel = updateMovementModel;
        this.preserveMyWaveAfterBulletCollision = preserveMyWaveAfterBulletCollision;
    }
}
