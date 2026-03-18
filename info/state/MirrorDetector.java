package oog.mega.saguaro.info.state;

import java.awt.geom.Point2D;
import java.util.Locale;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.MirrorDetectionProfile;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.perfectprediction.AntiMirrorPredictor;

public final class MirrorDetector {
    private static final int MIN_UNLOCK_SAMPLES = 64;
    private static final double MAX_UNLOCK_MEAN_ERROR = 1.0;
    private static final double UNLOCK_CONFIDENCE_SCALE = 2.5;

    private Saguaro robot;
    private double battlefieldWidth;
    private double battlefieldHeight;
    private long previousScanTime;
    private PhysicsUtil.PositionState previousRobotState;
    private PhysicsUtil.PositionState previousEnemyState;

    public void init(Saguaro robot, double battlefieldWidth, double battlefieldHeight) {
        if (robot == null) {
            throw new IllegalArgumentException("Mirror detector requires a non-null robot");
        }
        if (!(battlefieldWidth > 0.0) || !(battlefieldHeight > 0.0)) {
            throw new IllegalArgumentException("Mirror detector requires positive battlefield dimensions");
        }
        this.robot = robot;
        this.battlefieldWidth = battlefieldWidth;
        this.battlefieldHeight = battlefieldHeight;
        this.previousScanTime = Long.MIN_VALUE;
        this.previousRobotState = null;
        this.previousEnemyState = null;
    }

    public void onScannedRobot(RobotSnapshot robotSnapshot, EnemyInfo enemy) {
        if (robotSnapshot == null || enemy == null) {
            throw new IllegalArgumentException("Mirror detector requires non-null robot and enemy state");
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
        if (previousRobotState != null
                && previousEnemyState != null
                && previousScanTime == robotSnapshot.time - 1L) {
            PhysicsUtil.PositionState predictedEnemyState = AntiMirrorPredictor.predictNextTick(
                    previousRobotState,
                    previousEnemyState,
                    battlefieldWidth,
                    battlefieldHeight);
            double positionError = Point2D.distance(
                    predictedEnemyState.x,
                    predictedEnemyState.y,
                    currentEnemyState.x,
                    currentEnemyState.y);
            MirrorDetectionProfile.recordPredictionError(positionError);
            if (!MirrorDetectionProfile.wasPerfectPredictionEverUnlocked()
                    && MirrorDetectionProfile.getCombinedSampleCount() >= MIN_UNLOCK_SAMPLES) {
                double meanErrorUpperBound =
                        MirrorDetectionProfile.getCombinedMeanErrorUpperBound(UNLOCK_CONFIDENCE_SCALE);
                if (!Double.isNaN(meanErrorUpperBound) && meanErrorUpperBound <= MAX_UNLOCK_MEAN_ERROR) {
                    MirrorDetectionProfile.markEnabledEver();
                    announceUnlock(meanErrorUpperBound);
                }
            }
        }
        previousScanTime = robotSnapshot.time;
        previousRobotState = currentRobotState;
        previousEnemyState = currentEnemyState;
    }

    public boolean isPerfectPredictionUnlocked() {
        return MirrorDetectionProfile.isPerfectPredictionUnlocked(
                MIN_UNLOCK_SAMPLES,
                MAX_UNLOCK_MEAN_ERROR,
                UNLOCK_CONFIDENCE_SCALE);
    }

    public int getMirrorPredictionSampleCount() {
        return MirrorDetectionProfile.getCombinedSampleCount();
    }

    public double getMirrorPredictionMeanError() {
        return MirrorDetectionProfile.getCombinedMeanError();
    }

    private void announceUnlock(double meanErrorUpperBound) {
        if (robot == null) {
            return;
        }
        robot.out.println(String.format(
                Locale.US,
                "Mirror detector unlocked: mean error %.2f, upper bound %.2f over %d samples",
                MirrorDetectionProfile.getCombinedMeanError(),
                meanErrorUpperBound,
                MirrorDetectionProfile.getCombinedSampleCount()));
    }
}
