package oog.mega.saguaro;

public final class BotConfig {
    // Treat tiny floating-point gun-heat residues as ready-to-fire.
    public static final double GUN_HEAT_READY_EPSILON = 1e-9;

    private BotConfig() {
    }

    public static final class Debug {
        private Debug() {
        }

        public static final boolean ENABLE_WAVE_RENDERING = true;
        // Prints round diagnostics, the active mode and what was loaded for the opponent.
        public static final boolean PRINT_DIAGNOSTICS = false;
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

        // Maximum number of waves to include in the near-term planning/scoring horizon.
        public static final int MAX_WAVE_DEPTH = 2;
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
        public static final int REFERENCE_EXTRA_RANDOM_FAMILY_COUNT = 4;
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
        // Tick budget for each explicit CW/CCW anchor sub-segment before re-evaluating wave pass timing.
        public static final int ANCHOR_WALL_SMOOTHED_SEGMENT_TICKS = 10;
        // Waypoint distance used for short CW/CCW anchor sub-segments; intentionally longer than one segment can reach.
        public static final double ANCHOR_WALL_SMOOTHED_TARGET_DISTANCE = 100.0;
        // Inset applied only to generated CW/CCW path targets so they do not sit directly on the wall margin.
        public static final double PATH_TARGET_WALL_INSET = 6.0;
        // Keep path planners on opening anti-gravity until this many ticks before the opponent's first gun-ready tick.
        public static final int OPENING_ANTI_GRAVITY_STOP_LEAD_TICKS = 8;

        // Number of random tails considered when extending a committed perfect-prediction path.
        public static final int RANDOM_TAIL_CANDIDATE_COUNT = 8;
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
        // When shield cooldown is within this many ticks of an integer boundary, round up to avoid edge-tick fire failures.
        public static final double SHIELD_READY_TICK_EDGE_EPSILON = 1e-6;
        // Extra slack kept between an aggressive shot and the next required shield fire time.
        public static final long AGGRESSIVE_SHOT_SHIELD_SAFETY_TICKS = 3L;
        // Range inside which shield mode is willing to take the direct-finisher shot immediately.
        public static final double AGGRESSIVE_CLOSE_RANGE_RADIUS = 60.0;
        // Opening-round grace period before shield mode considers idle aggressive shots.
        public static final long EARLY_ROUND_IDLE_AGGRESSION_GUARD_TICKS = 100L;
        // Opening-round grace period before plain BulletShielding spends spare shield slack on attacks.
        public static final long EARLY_ROUND_DEADLINE_AGGRESSION_GUARD_TICKS = 50L;
        // Temporary toggle for direct attacks that are justified only by estimated spare shield slack.
        public static final boolean ENABLE_SHIELD_SLACK_AGGRESSION = true;
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
        // Number of recent motion steps matched when searching for a repeated enemy pattern.
        public static final int LEARNED_PATTERN_CONTEXT_LENGTH = 12;
        // Maximum number of observed enemy motion steps retained for learned-pattern replay.
        public static final int LEARNED_PATTERN_MAX_HISTORY_STEPS = 2048;
        // Maximum number of future learned-pattern steps replayed from a matched historical suffix.
        public static final int LEARNED_PATTERN_MAX_SCRIPT_TICKS = 96;
        // Quantization buckets used for raw turn-delta pattern matching.
        public static final int LEARNED_PATTERN_TURN_BUCKET_COUNT = 19;
        // Quantization buckets used for raw velocity pattern matching.
        public static final int LEARNED_PATTERN_VELOCITY_BUCKET_COUNT = 17;
        // Confidence multiplier used when ranking competing precise-prediction predictors.
        public static final double PREDICTOR_SELECTION_CONFIDENCE_SCALE = 2.5;
    }

    public static final class ScoreMax {
        private ScoreMax() {
        }

        // Number of path families from the prior tick that remain eligible for reuse.
        public static final int MAX_CARRIED_FORWARD_FAMILIES = 2;
        // Number of candidate offensive powers sampled when building the global power bracket.
        public static final int GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT = 4;
        // Default gun power used when tracking a likely future shot without firing immediately.
        public static final double DEFAULT_TRACKING_FIRE_POWER = 2.0;
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
    }

    public static final class ModeSelection {
        private ModeSelection() {
        }

        // If non-null, bypasses normal mode selection and locks the bot into this mode.
        // Valid values: null, "scoreMax", "bulletShield", "movingBulletShield",
        //               "perfectPrediction", "shotDodger", "wavePoison", "wavePoisonShift", "antiBasicSurfer"
        public static final String LOCKED_MODE = null;

        // Whether an opponent with no settled mode goes through mode selection. Off, it gets ScoreMax and
        // never settles, so its data keeps accumulating. Training needs this on to settle new opponents.
        public static final boolean EXPLORE_UNSETTLED_OPPONENTS = false;

        // Scales the mode-selection confidence bound used to disqualify candidates.
        public static final double CONFIDENCE_SCALE = 6.0;
        // Invisible share offset subtracted before mapping into comparison-logit space.
        public static final double COMPARISON_SHARE_OFFSET = 0.01;
        // Exploratory mean assigned to modes with no score evidence yet.
        public static final double UNTESTED_MODE_MEAN = 0.90;
        // Raw score delta corresponding to one posterior evidence unit.
        public static final double POSTERIOR_SCORE_UNIT = 24.0;
        // Prior score mass assigned to each mode before live evidence arrives.
        public static final double PRIOR_SCORE = 48.0;
        // Score horizon over which the prior fades out relative to observed results.
        public static final double PRIOR_FADE_SCORE = 500.0;
        // Beta prior parameters for per-mode win-share uncertainty.
        public static final double UNCERTAINTY_PRIOR_ALPHA = 1.0;
        public static final double UNCERTAINTY_PRIOR_BETA = 1.0;
        // Extra comparison-space uncertainty margin granted while a mode still has little score evidence.
        public static final double EARLY_DISQUALIFICATION_MAX_BONUS = 1.0;
        // Raw-score horizon over which the early disqualification bonus decays to zero.
        public static final double EARLY_DISQUALIFICATION_BONUS_FADE_SCORE = 2_000.0;
        // Minimum consecutive rounds to spend in a mode before round-start switching is allowed.
        public static final int MIN_ROUNDS_BEFORE_SWITCH = 2;
        // AntiBasicSurfer must already be winning by at least this observed score share before it can be selected.
        public static final double ANTI_BASIC_SURFER_MIN_SELECTION_SCORE = 0.80;
        // AntiBasicSurfer must beat ScoreMax by more than this observed score-share margin before it can be selected.
        public static final double ANTI_BASIC_SURFER_MIN_SCOREMAX_ADVANTAGE = 0.01;
        // Minimum total raw score required for the best mode before mode selection can settle.
        public static final double MIN_SETTLED_MODE_TOTAL_SCORE = 15_000.0;
        // Once every non-disqualified mode reaches this raw-score total, force settling to the highest-mean mode.
        public static final double FORCED_SETTLED_MODE_TOTAL_SCORE = 50_000.0;
        // Settle on the highest-mean mode once no other non-disqualified mode's upper bound exceeds its lower
        // bound by more than this share: what further exploration could still gain is then below it. Near 100%
        // every mode's bounds crowd the ceiling, so none can disqualify another and only this ends exploration.
        public static final double SETTLE_SHARE_MARGIN = 0.01;
    }

    public static final class Learning {
        private Learning() {
        }

        // Default prior score share used before any persisted ScoreMax history exists.
        public static final double DEFAULT_SCORE_SHARE_PRIOR = 0.5;

        // Default KDE bandwidth for movement distributions.
        public static final double DEFAULT_MOVEMENT_KDE_BANDWIDTH = 0.36;

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
