package oog.mega.saguaro.info.state;

import java.util.Locale;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.perfectprediction.AntiMirrorPredictor;
import oog.mega.saguaro.mode.perfectprediction.CopyAntiMirrorPredictor;
import oog.mega.saguaro.mode.perfectprediction.PerpendicularAntiMirrorPredictor;
import oog.mega.saguaro.mode.perfectprediction.PrecisePredictionProfile;
import oog.mega.saguaro.mode.perfectprediction.RamPredictors;
import oog.mega.saguaro.mode.perfectprediction.ReactiveOpponentPredictor;
import oog.mega.saguaro.mode.perfectprediction.ReactivePredictorId;

public final class PrecisePredictionTracker {
    private static final double MIN_ACTIVITY_WEIGHT = 0.1;
    private static final double DELTA_ERROR_SCALE = 1.0 / Math.sqrt(2.0);

    private Saguaro robot;
    private double battlefieldWidth;
    private double battlefieldHeight;
    private long previousScanTime;
    private PhysicsUtil.PositionState previousRobotState;
    private PhysicsUtil.PositionState previousEnemyState;
    private boolean trackingEnabled;
    private boolean perfectPredictionUnlocked;

    public void init(Saguaro robot, double battlefieldWidth, double battlefieldHeight) {
        if (robot == null) {
            throw new IllegalArgumentException("Precise-prediction tracker requires a non-null robot");
        }
        if (!(battlefieldWidth > 0.0) || !(battlefieldHeight > 0.0)) {
            throw new IllegalArgumentException("Precise-prediction tracker requires positive battlefield dimensions");
        }
        this.robot = robot;
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        this.previousScanTime = Long.MIN_VALUE;
        this.previousRobotState = null;
        this.previousEnemyState = null;
        this.trackingEnabled = false;
    }

    public void onScannedRobot(RobotSnapshot robotSnapshot, EnemyInfo enemy) {
        if (robotSnapshot == null || enemy == null) {
            throw new IllegalArgumentException("Precise-prediction tracker requires non-null robot and enemy state");
        }
        PhysicsUtil.PositionState currentRobotState = new PhysicsUtil.PositionState(
                robotSnapshot.x,
                robotSnapshot.y,
                robotSnapshot.heading,
                robotSnapshot.velocity);
        PhysicsUtil.PositionState currentEnemyState = new PhysicsUtil.PositionState(
                enemy.x,
                enemy.y,
                enemy.heading,
                enemy.velocity);
        if (trackingEnabled
                && previousRobotState != null
                && previousEnemyState != null
                && previousScanTime == robotSnapshot.time - 1L) {
            evaluatePredictors(currentEnemyState);
        }
        previousScanTime = robotSnapshot.time;
        previousRobotState = currentRobotState;
        previousEnemyState = currentEnemyState;
    }

    public void setTrackingEnabled(boolean enabled) {
        trackingEnabled = enabled;
    }

    public boolean isPerfectPredictionUnlocked() {
        return perfectPredictionUnlocked;
    }

    public ReactiveOpponentPredictor createBestPredictor() {
        ReactivePredictorId predictorId = PrecisePredictionProfile.bestPredictorId(
                false,
                BotConfig.PerfectPrediction.MIN_UNLOCK_SAMPLES,
                BotConfig.PerfectPrediction.MAX_UNLOCK_MEAN_DELTA_ERROR,
                BotConfig.PerfectPrediction.UNLOCK_CONFIDENCE_SCALE);
        ReactiveOpponentPredictor predictor = buildPredictor(predictorId);
        return predictor != null ? predictor : AntiMirrorPredictor.predictor();
    }

    public ReactivePredictorId getBestPredictorId() {
        return PrecisePredictionProfile.bestPredictorId(
                false,
                BotConfig.PerfectPrediction.MIN_UNLOCK_SAMPLES,
                BotConfig.PerfectPrediction.MAX_UNLOCK_MEAN_DELTA_ERROR,
                BotConfig.PerfectPrediction.UNLOCK_CONFIDENCE_SCALE);
    }

    private void evaluatePredictors(PhysicsUtil.PositionState actualNextEnemyState) {
        for (ReactivePredictorId predictorId : ReactivePredictorId.values()) {
            ReactiveOpponentPredictor predictor = buildPredictor(
                    predictorId);
            if (predictor == null) {
                continue;
            }
            PhysicsUtil.PositionState predictedEnemyState = predictor.predictNextState(
                    0,
                    previousRobotState,
                    previousEnemyState,
                    battlefieldWidth,
                    battlefieldHeight);
            DeltaPredictionScore score = evaluatePredictionInDeltaSpace(predictedEnemyState, actualNextEnemyState);
            PrecisePredictionProfile.recordPredictionError(predictorId, score.error, score.sampleWeight);
        }
        if (!perfectPredictionUnlocked) {
            ReactivePredictorId unlockedPredictorId = PrecisePredictionProfile.bestPredictorId(
                    true,
                    BotConfig.PerfectPrediction.MIN_UNLOCK_SAMPLES,
                    BotConfig.PerfectPrediction.MAX_UNLOCK_MEAN_DELTA_ERROR,
                    BotConfig.PerfectPrediction.UNLOCK_CONFIDENCE_SCALE);
            if (unlockedPredictorId != null) {
                perfectPredictionUnlocked = true;
                announceUnlock(unlockedPredictorId);
            }
        }
    }

    private DeltaPredictionScore evaluatePredictionInDeltaSpace(PhysicsUtil.PositionState predictedEnemyState,
                                                                PhysicsUtil.PositionState actualNextEnemyState) {
        if (previousEnemyState == null) {
            throw new IllegalStateException("Delta-space prediction scoring requires a previous enemy state");
        }

        double maxTurnDelta = PhysicsUtil.maxTurnRateRadians(previousEnemyState.velocity);
        double minVelocityDelta = PhysicsUtil.minVelocityDelta(previousEnemyState.velocity);
        double maxVelocityDelta = PhysicsUtil.maxVelocityDelta(previousEnemyState.velocity);

        double actualTurnDelta = MathUtils.normalizeAngle(actualNextEnemyState.heading - previousEnemyState.heading);
        double predictedTurnDelta = MathUtils.normalizeAngle(predictedEnemyState.heading - previousEnemyState.heading);
        double actualVelocityDelta = actualNextEnemyState.velocity - previousEnemyState.velocity;
        double predictedVelocityDelta = predictedEnemyState.velocity - previousEnemyState.velocity;

        double normalizedActualTurnDelta = normalizeToUnit(actualTurnDelta, -maxTurnDelta, maxTurnDelta);
        double normalizedPredictedTurnDelta = normalizeToUnit(predictedTurnDelta, -maxTurnDelta, maxTurnDelta);
        double normalizedActualVelocityDelta = normalizeToUnit(actualVelocityDelta, minVelocityDelta, maxVelocityDelta);
        double normalizedPredictedVelocityDelta =
                normalizeToUnit(predictedVelocityDelta, minVelocityDelta, maxVelocityDelta);

        double turnError = Math.abs(normalizedPredictedTurnDelta - normalizedActualTurnDelta);
        double velocityError = Math.abs(normalizedPredictedVelocityDelta - normalizedActualVelocityDelta);
        double error = Math.hypot(turnError, velocityError) * DELTA_ERROR_SCALE;

        double turnActivity = maxTurnDelta > 1e-9
                ? clamp(Math.abs(actualTurnDelta) / maxTurnDelta, 0.0, 1.0)
                : 0.0;
        double velocityScale = Math.max(Math.abs(minVelocityDelta), Math.abs(maxVelocityDelta));
        double velocityActivity = velocityScale > 1e-9
                ? clamp(Math.abs(actualVelocityDelta) / velocityScale, 0.0, 1.0)
                : 0.0;
        double activity = Math.max(turnActivity, velocityActivity);
        double sampleWeight = MIN_ACTIVITY_WEIGHT + (1.0 - MIN_ACTIVITY_WEIGHT) * activity;

        return new DeltaPredictionScore(error, sampleWeight);
    }

    private ReactiveOpponentPredictor buildPredictor(ReactivePredictorId predictorId) {
        if (predictorId == null) {
            return null;
        }
        if (predictorId == ReactivePredictorId.ANTI_MIRROR) {
            return AntiMirrorPredictor.predictor();
        }
        if (predictorId == ReactivePredictorId.ANTI_MIRROR_PERPENDICULAR) {
            return PerpendicularAntiMirrorPredictor.predictor();
        }
        if (predictorId == ReactivePredictorId.ANTI_MIRROR_COPY) {
            return CopyAntiMirrorPredictor.predictor();
        }
        if (predictorId == ReactivePredictorId.RAM_DIRECT) {
            return RamPredictors.directPredictor();
        }
        if (predictorId == ReactivePredictorId.RAM_DIRECT_BACK_AS_FRONT) {
            return RamPredictors.directBackAsFrontPredictor();
        }
        if (predictorId == ReactivePredictorId.RAM_LINEAR) {
            return RamPredictors.linearPredictor();
        }
        if (predictorId == ReactivePredictorId.RAM_LINEAR_BACK_AS_FRONT) {
            return RamPredictors.linearBackAsFrontPredictor();
        }
        throw new IllegalArgumentException("Unsupported precise-prediction predictor " + predictorId);
    }

    private void announceUnlock(ReactivePredictorId predictorId) {
        if (robot == null || predictorId == null) {
            return;
        }
        robot.out.println(String.format(
                Locale.US,
                "PrecisePrediction unlocked via %s: rolling mean weighted delta error %.2f, upper bound %.2f over %.1f weighted samples (%d raw)",
                predictorId.displayName(),
                PrecisePredictionProfile.getCombinedMeanError(predictorId),
                PrecisePredictionProfile.getCombinedMeanErrorUpperBound(
                        predictorId,
                        BotConfig.PerfectPrediction.UNLOCK_CONFIDENCE_SCALE),
                PrecisePredictionProfile.getCombinedWeightedSampleCount(predictorId),
                PrecisePredictionProfile.getCombinedRawSampleCount(predictorId)));
    }

    private static double normalizeToUnit(double value, double minValue, double maxValue) {
        if (maxValue <= minValue + 1e-9) {
            return 0.5;
        }
        return clamp((value - minValue) / (maxValue - minValue), 0.0, 1.0);
    }

    private static double clamp(double value, double minValue, double maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static final class DeltaPredictionScore {
        final double error;
        final double sampleWeight;

        DeltaPredictionScore(double error, double sampleWeight) {
            if (!Double.isFinite(error) || error < 0.0) {
                throw new IllegalArgumentException("Delta prediction score requires a finite non-negative error");
            }
            if (!Double.isFinite(sampleWeight) || sampleWeight <= 0.0 || sampleWeight > 1.0 + 1e-9) {
                throw new IllegalArgumentException("Delta prediction score requires a finite sample weight in (0, 1]");
            }
            this.error = error;
            this.sampleWeight = sampleWeight;
        }
    }
}
