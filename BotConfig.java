package oog.mega.saguaro;

public final class BotConfig {
    private BotConfig() {
    }

    public static final class Debug {
        private Debug() {
        }

        public static final boolean ENABLE_WAVE_RENDERING = true;
        // Capture expensive WaveLog trace-source diagnostics when debugging KD-tree issues.
        public static final boolean ENABLE_TRACE_SOURCE_CAPTURE = false;
    }

    public static final class Radar {
        private Radar() {
        }

        // Extra overscan padding applied around the opponent bearing during focused tracking.
        public static final double SCAN_WIDTH = Math.PI / 32.0;
        // How many edge reversals to tolerate before falling back to a full sweep.
        public static final int REVERSALS_BEFORE_SWEEP = 3;
    }

    public static final class Gun {
        private Gun() {
        }

        // Fallback movement bandwidth used when no learned movement distribution is available.
        public static final double DEFAULT_OPPONENT_MOVEMENT_BANDWIDTH = 1.0;
        // Maximum naive GF scan step used during peak-search aiming.
        public static final double PEAK_SCAN_STEP = 0.02;
        // Number of targeting samples needed before hit-rate scaling reaches full confidence.
        public static final int TARGETING_DATA_FULL_CONFIDENCE_SAMPLES = 24;
        // Lower bound for hit-rate scaling while targeting data is still sparse.
        public static final double MIN_TARGETING_DATA_HIT_RATE_SCALE = 0.5;
    }

    public static final class Movement {
        private Movement() {
        }

        // Number of safe-point candidates to keep on the first real wave.
        public static final int K_SAFE_POINTS = 2;
        // Number of safe points to keep when the first wave is deferred and virtual.
        public static final int DEFERRED_VIRTUAL_WAVE_SAFE_POINTS = 1;
        // Number of safe points to keep for later waves in multi-wave planning.
        public static final int LATER_WAVE_SAFE_POINTS = 1;
        // Maximum number of waves to include in the near-term planning/scoring horizon.
        public static final int MAX_WAVE_DEPTH = 2;
        // Distance used when building large pre-fire waypoint targets around the opponent.
        public static final double PRE_FIRE_TARGET_DISTANCE = 1000.0;
        // Cap applied when projecting future enemy gun heat for virtual-wave planning.
        public static final double MAX_EFFECTIVE_VIRTUAL_GUN_HEAT = 1.6;
        // Allows disabling virtual-wave planning without changing movement code paths.
        public static final boolean ENABLE_VIRTUAL_WAVES = true;

        // Anchor-family count for single-wave planning.
        public static final int SINGLE_WAVE_ANCHOR_FAMILY_COUNT = 3;
        // Anchor-family count for dual-wave planning.
        public static final int DUAL_WAVE_ANCHOR_FAMILY_COUNT = 9;
        // Baseline number of random path families generated each tick.
        public static final int BASE_RANDOM_FAMILY_COUNT = 4;
        // Extra random families granted around the reference planning horizon.
        public static final int REFERENCE_EXTRA_RANDOM_FAMILY_COUNT = 7;
        // Maximum number of previously-selected families to carry forward unchanged.
        public static final int MAX_REUSED_FAMILY_COUNT = 1;
        // Optional primary-family slots beyond the mandatory anchor families.
        public static final int REFERENCE_PRIMARY_FAMILY_OPTIONAL_SLOTS = 4;
        // Reference planning horizon used to normalize path-family budgets.
        public static final double REFERENCE_PLANNING_HORIZON_TICKS = 28.0;
        // Exponent controlling how aggressively the family budget scales with horizon length.
        public static final double FAMILY_BUDGET_SCALE_EXPONENT = 0.75;
        public static final double MIN_FAMILY_BUDGET_SCALE = 0.25;
        public static final double MAX_FAMILY_BUDGET_SCALE = 1.6;
        // Global multiplier for the computed path-family budget.
        public static final double ADDITIONAL_BUDGET_MULTIPLIER = 1.0;
        // Early turns hold the minimum budget until this tick.
        public static final long EARLY_TURN_MIN_BUDGET_HOLD_TICK = 25L;
        // Early-turn budget ramps to full size by this tick.
        public static final long FULL_EARLY_TURN_BUDGET_TICK = 70L;
        // Minimum family-budget multiplier during the opening-turn ramp.
        public static final double MIN_EARLY_TURN_BUDGET_MULTIPLIER = 0.5;
        // Budget multiplier applied when extra planning waves are present.
        public static final double EXTRA_PLANNING_WAVE_BUDGET_MULTIPLIER = 0.9;
        // Furthest random segment target to sample from the current state.
        public static final double MAX_SEGMENT_TARGET_DISTANCE = 150.0;
        // Longest random segment duration explored during path generation.
        public static final int MAX_SEGMENT_DURATION_TICKS = 30;
        // Maximum coordinate wiggle applied when mutating an existing family target.
        public static final double MAX_MUTATION_TARGET_WIGGLE = 10.0;
        // Maximum tick delta applied when mutating an existing family duration.
        public static final int MAX_MUTATION_DURATION_DELTA = 5;
        // Probe distance used when CW/CCW path legs wall-smooth against the field boundary.
        public static final double PATH_WALL_SMOOTHING_STICK_LENGTH = 140.0;
        // Angular step used when rotating a wall-smoothed travel angle back into the field.
        public static final double PATH_WALL_SMOOTHING_ANGLE_STEP_RADIANS = Math.toRadians(2.0);

        // Number of random tails considered when extending a committed perfect-prediction path.
        public static final int RANDOM_TAIL_CANDIDATE_COUNT = 15;
        // Furthest random tail-leg target from the current committed endpoint.
        public static final double MAX_TAIL_SEGMENT_TARGET_DISTANCE = 150.0;
        // Longest random tail leg explored for perfect-prediction carry-forward tails.
        public static final int MAX_TAIL_SEGMENT_DURATION_TICKS = 30;

        // Minimum spacing between GF danger extrema candidates as a fraction of the hitbox interval.
        public static final double MIN_GF_SEPARATION_FRACTION = 0.15;
        // Number of evenly-spaced samples used before refining GF danger extrema.
        public static final int GF_EXTREMA_SCAN_STEPS = 16;
    }

    public static final class Shield {
        private Shield() {
        }

        // Number of candidate bullet powers sampled when searching for a viable shield shot.
        public static final int POWER_SAMPLE_COUNT = 10;
        // Maximum number of recent snapshots retained for shield-wave reconstruction.
        public static final int SNAPSHOT_HISTORY_LIMIT = 6;
        // Extra slack kept between an aggressive shot and the next required shield fire time.
        public static final long AGGRESSIVE_SHOT_SHIELD_SAFETY_TICKS = 2L;
        // Range inside which shield mode is willing to take the direct-finisher shot immediately.
        public static final double AGGRESSIVE_CLOSE_RANGE_RADIUS = 60.0;
        // Opening-round grace period before shield mode considers idle aggressive shots.
        public static final long EARLY_ROUND_IDLE_AGGRESSION_GUARD_TICKS = 100L;
        // Scales enemy-gun-heat cooldown into the idle-shot threshold in shield mode.
        public static final double IDLE_AGGRESSION_GUN_HEAT_MULTIPLIER = 2.0;
        // Start braking this many ticks before the enemy's opening gun heat is expected to reach zero.
        public static final int OPENING_CENTER_STOP_LEAD_TICKS = 5;
        // Emergency stop radius for the opening center-reposition when the current motion is closing distance.
        public static final double OPENING_CENTER_EMERGENCY_STOP_DISTANCE = 200.0;
    }

    public static final class PerfectPrediction {
        private PerfectPrediction() {
        }

        // Firepower used by the committed perfect-prediction mode once a firing line is available.
        public static final double FIRE_POWER = 3.0;
        // Start committing the opening path this many ticks before the first expected shot.
        public static final int OPENING_SHOT_COMMIT_LEAD_TICKS = 5;
        // Safety margin, in gun-cooldown lengths, used when choosing the committed tail horizon.
        public static final double TAIL_DURATION_GUN_HEAT_MARGIN = 1.5;
        // Rolling sample window retained for each reactive-predictor error profile.
        public static final int MAX_RECENT_PROFILE_SAMPLES = 500;
        // Minimum evidence required before the perfect-prediction detector can unlock.
        public static final int MIN_UNLOCK_SAMPLES = 500;
        // Highest acceptable weighted delta-error upper bound for unlocking perfect prediction.
        public static final double MAX_UNLOCK_MEAN_DELTA_ERROR = 0.20;
        // Confidence multiplier used when comparing predictor error bounds for unlock decisions.
        public static final double UNLOCK_CONFIDENCE_SCALE = 2.5;
    }

    public static final class ScoreMax {
        private ScoreMax() {
        }

        // Number of path families from the prior tick that remain eligible for reuse.
        public static final int MAX_CARRIED_FORWARD_FAMILIES = 2;
        // Number of candidate offensive powers sampled when building the global power bracket.
        public static final int GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT = 6;
        // Default gun power used when tracking a likely future shot without firing immediately.
        public static final double DEFAULT_TRACKING_FIRE_POWER = 2.0;
        // Lower bound on the prior-weight blend used when little score history exists.
        public static final double DEFAULT_PRIOR_WEIGHT_FLOOR = 0.35;
        // Exponent applied to score-history evidence before blending with the prior.
        public static final double DEFAULT_PRIOR_WEIGHT_EXPONENT = 0.5;
        // Energy-lead scale in the scoremax prior win-probability heuristic.
        public static final double DEFAULT_ENERGY_LEAD_SCALE = 8.0;
        // Energy-lead offset in the scoremax prior win-probability heuristic.
        public static final double DEFAULT_ENERGY_LEAD_OFFSET = 20.0;
        // Uncertainty scale applied when both bots are in low-energy endgames.
        public static final double DEFAULT_LOW_ENERGY_UNCERTAINTY_SCALE = 30.0;
        // Converts one tick of gun readiness into score-equivalent energy.
        public static final double DEFAULT_READY_TICK_ENERGY_EQUIVALENT = 0.35;
        // Ready-tick lead is capped to keep the heuristic bounded.
        public static final int MAX_READY_TICK_LEAD = 8;
        // Maximum number of immediate branched shot events to model explicitly.
        public static final int MAX_BRANCHED_SHOT_EVENTS = 4;
        // Baseline enemy continuation firepower assumed beyond the explicitly modeled horizon.
        public static final double DEFAULT_ENEMY_CONTINUATION_POWER = 2.0;
        // Preserve the end-of-round EV bonus path behind a configurable toggle.
        public static final boolean ENABLE_END_OF_ROUND_BONUS_EV = true;
        // Clamp on the prior win probability before blending with live evidence.
        public static final double MIN_PRIOR_WIN_PROBABILITY = 0.05;
        public static final double MAX_PRIOR_WIN_PROBABILITY = 0.95;
        // Initial target prior for using ScoreMax against a fresh opponent.
        public static final double FIRST_BATTLE_SCOREMAX_PRIOR_TARGET = 0.90;
        // Number of rounds to keep the first-battle ScoreMax prior active.
        public static final int FIRST_BATTLE_SCOREMAX_PRIOR_ROUNDS = 10;
    }

    public static final class ModeSelection {
        private ModeSelection() {
        }

        // Scales the logit-space confidence interval used for mode selection and settling.
        public static final double CONFIDENCE_SCALE = 7.0;
        // Raw score delta corresponding to one posterior evidence unit.
        public static final double POSTERIOR_SCORE_UNIT = 24.0;
        // Prior score mass assigned to each mode before live evidence arrives.
        public static final double PRIOR_SCORE = 48.0;
        // Score horizon over which the prior fades out relative to observed results.
        public static final double PRIOR_FADE_SCORE = 500.0;
        // Beta prior parameters for per-mode win-share uncertainty.
        public static final double UNCERTAINTY_PRIOR_ALPHA = 1.0;
        public static final double UNCERTAINTY_PRIOR_BETA = 1.0;
        // Confidence-interval width below which a mode is considered settled.
        public static final double SETTLED_CI_WIDTH = 0.10;
    }

    public static final class Learning {
        private Learning() {
        }

        // Default prior score share used before any persisted ScoreMax history exists.
        public static final double DEFAULT_SCORE_SHARE_PRIOR = 0.5;

        // Default smoothing width for the targeting KD-model context weights.
        public static final double DEFAULT_TARGETING_CONTEXT_WEIGHT_SIGMA = 0.4;
        // Default smoothing width for the movement KD-model context weights.
        public static final double DEFAULT_MOVEMENT_CONTEXT_WEIGHT_SIGMA = 0.55;
        // Default KDE bandwidth for targeting distributions.
        public static final double DEFAULT_TARGETING_KDE_BANDWIDTH = 0.36;
        // Default KDE bandwidth for movement distributions.
        public static final double DEFAULT_MOVEMENT_KDE_BANDWIDTH = 0.36;
        // Learning rate for model feature weights in WaveLog.
        public static final double WEIGHT_LEARNING_RATE = 0.035;
        // Learning rate for log-space model parameters such as bandwidth and sigma.
        public static final double LOG_PARAMETER_LEARNING_RATE = 0.02;
        // Pull back toward the default model each update; zero keeps the default fixed.
        public static final double MODEL_DEFAULT_PULL_RATE = 0.0;
        // Clip applied to WaveLog feature-weight gradients.
        public static final double WEIGHT_GRADIENT_CLIP = 4.0;
        // Clip applied to WaveLog bandwidth/sigma gradients.
        public static final double LOG_PARAMETER_GRADIENT_CLIP = 3.0;
        // Lower and upper bounds for learned KDE bandwidths.
        public static final double MIN_KDE_BANDWIDTH = 0.08;
        public static final double MAX_KDE_BANDWIDTH = 0.75;
        // Lower and upper bounds for learned context-weight sigmas.
        public static final double MIN_CONTEXT_WEIGHT_SIGMA = 0.12;
        public static final double MAX_CONTEXT_WEIGHT_SIGMA = 1.25;
        // Minimum samples required before the targeting/movement models are treated as active.
        public static final int MIN_TARGETING_SAMPLE_COUNT = 2;
        public static final int MIN_MOVEMENT_SAMPLE_COUNT = 2;

        // Global hit-rate prior strength for the lightweight enemy hit-rate baseline models.
        public static final double ENEMY_HIT_RATE_GLOBAL_PRIOR_STRENGTH = 10.0;
        // Additional prior strength for flight-time-specific enemy hit-rate bins.
        public static final double ENEMY_HIT_RATE_FLIGHT_PRIOR_STRENGTH = 9.0;
        // Clamp range applied to enemy hit-rate estimates before they feed into planning.
        public static final double ENEMY_HIT_RATE_MIN = 0.02;
        public static final double ENEMY_HIT_RATE_MAX = 0.6;
        // Effective sample cap for persisted bullet-power hit-rate evidence.
        public static final double BULLET_POWER_MAX_EFFECTIVE_SHOTS = 256.0;
    }
}
