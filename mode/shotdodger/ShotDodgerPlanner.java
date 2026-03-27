package oog.mega.saguaro.mode.shotdodger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.MovementController;
import oog.mega.saguaro.movement.PathGenerationContext;
import oog.mega.saguaro.movement.PathWaveIntersection;
import robocode.Rules;

final class ShotDodgerPlanner {
    private static final double MIN_FIRE_POWER = 0.1;
    private static final double PREDICTED_SHOT_SIGMA_GUESS_FACTOR = 0.20;
    private static final double DISTANCE_ADJUSTMENT_SCALE = 18.0;
    private static final double DISTANCE_ADJUSTMENT_MIN_DISTANCE = 150.0;
    private static final double WAVE_TIME_WEIGHT_SCALE = 0.015;

    private Info info;
    private MovementController movement;
    private GunController gun;
    private ShotDodgerObservationProfile observationProfile;
    private ShotDodgerPowerScorer powerScorer;
    private CandidatePath lastSelectedPath;
    private List<CandidatePath> lastSelectedFamilyPaths = new ArrayList<CandidatePath>();
    private List<PathWaveIntersection> lastSelectedPathIntersections = new ArrayList<PathWaveIntersection>();
    private long lastSelectedWaveDangerRevision;

    void init(Info info,
              MovementController movement,
              GunController gun,
              ShotDodgerObservationProfile observationProfile) {
        if (info == null || movement == null || gun == null || observationProfile == null) {
            throw new IllegalArgumentException(
                    "ShotDodgerPlanner requires non-null info, movement, gun, and observationProfile");
        }
        this.info = info;
        this.movement = movement;
        this.gun = gun;
        this.observationProfile = observationProfile;
        if (this.powerScorer == null) {
            this.powerScorer = new ShotDodgerPowerScorer();
        }
        this.powerScorer.init(info);
        this.lastSelectedPath = null;
        this.lastSelectedFamilyPaths = new ArrayList<CandidatePath>();
        this.lastSelectedPathIntersections = new ArrayList<PathWaveIntersection>();
        this.lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();
    }

    BattlePlan getBestPlan() {
        RobotSnapshot robotState = info.captureRobotSnapshot();
        invalidateSelectedPathStateIfWaveDangerChanged();

        int firstFiringTickOffset = firstFiringTickOffset(robotState.gunHeat);
        PathGenerationContext pathGenerationContext = movement.createPathGenerationContext();
        pathGenerationContext.minPathTicks = firstFiringTickOffset;

        List<CandidatePath> paths = movement.generateCandidatePaths(pathGenerationContext, lastSelectedFamilyPaths);
        CandidatePath bestPath = selectBestPath(paths);
        if (bestPath == null) {
            return new BattlePlan(0.0, 0.0, 0.0, 0.0);
        }

        lastSelectedPath = bestPath;
        lastSelectedFamilyPaths = Collections.singletonList(bestPath);
        lastSelectedPathIntersections = bestPath.pathIntersections != null
                ? bestPath.pathIntersections
                : movement.collectPathWaveIntersections(bestPath, null);
        lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();

        double[] movementInstruction = firstTickMovementInstruction(bestPath, robotState);
        ShotDodgerPowerScorer.PowerSelection powerSelection = powerScorer.selectBestPower(
                bestPath,
                lastSelectedPathIntersections,
                robotState,
                firstFiringTickOffset);
        GunInstruction gunInstruction = buildGunInstruction(
                bestPath,
                robotState,
                firstFiringTickOffset,
                powerSelection != null ? powerSelection.firePower : 0.0);
        return new BattlePlan(
                movementInstruction[0],
                movementInstruction[1],
                gunInstruction.gunTurnAngle,
                gunInstruction.firePower);
    }

    CandidatePath getLastSelectedPath() {
        return lastSelectedPath;
    }

    List<PathWaveIntersection> getLastSelectedPathIntersections() {
        return lastSelectedPathIntersections;
    }

    String describeSkippedTurnDiagnostics() {
        return movement != null ? movement.describeLatestPathPlanningDiagnostics() : "planning=n/a";
    }

    private static int firstFiringTickOffset(double currentGunHeat) {
        if (currentGunHeat < 0.001) {
            return 0;
        }
        return (int) Math.floor((currentGunHeat - 0.001) / 0.1) + 1;
    }

    private CandidatePath selectBestPath(List<CandidatePath> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        CandidatePath bestPath = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (CandidatePath path : paths) {
            if (path == null) {
                continue;
            }
            List<PathWaveIntersection> intersections = path.pathIntersections != null
                    ? path.pathIntersections
                    : movement.collectPathWaveIntersections(path, null);
            double cost = scorePath(path, intersections);
            if (bestPath == null
                    || cost < bestCost - 1e-9
                    || (Math.abs(cost - bestCost) <= 1e-9 && isBetterTiebreak(path, bestPath))) {
                bestPath = path;
                bestCost = cost;
            }
        }
        return bestPath;
    }

    private static boolean isBetterTiebreak(CandidatePath candidate, CandidatePath incumbent) {
        if (candidate.wallHitDamage < incumbent.wallHitDamage - 1e-9) {
            return true;
        }
        if (candidate.wallHitDamage > incumbent.wallHitDamage + 1e-9) {
            return false;
        }
        return candidate.totalDanger < incumbent.totalDanger - 1e-9;
    }

    private double scorePath(CandidatePath path, List<PathWaveIntersection> intersections) {
        double predictedDanger = 0.0;
        int bestExpertOrdinal = observationProfile.currentBestMovementExpertId().ordinal();
        int scoredWaveCount = 0;
        long currentTime = info.getRobot().getTime();

        if (intersections != null) {
            for (PathWaveIntersection intersection : intersections) {
                double waveDanger = scoreIntersection(intersection, bestExpertOrdinal, currentTime);
                if (!Double.isFinite(waveDanger)) {
                    continue;
                }
                predictedDanger += waveDanger;
                scoredWaveCount++;
            }
        }

        if (scoredWaveCount == 0) {
            predictedDanger = path.totalDanger;
        }

        predictedDanger += path.wallHitDamage;
        predictedDanger += Math.max(0.0, path.ramEnergyLoss);
        predictedDanger += distanceAdjustment(path);
        return predictedDanger;
    }

    private double scoreIntersection(PathWaveIntersection intersection,
                                     int bestExpertOrdinal,
                                     long currentTime) {
        if (intersection == null
                || intersection.wave == null
                || intersection.exposedGfIntervals == null
                || intersection.exposedGfIntervals.isEmpty()) {
            return Double.NaN;
        }
        Wave wave = intersection.wave;
        double[] predictedCenters = wave.fireTimeRenderGfMarkers;
        if (predictedCenters == null
                || bestExpertOrdinal < 0
                || bestExpertOrdinal >= predictedCenters.length
                || !Double.isFinite(predictedCenters[bestExpertOrdinal])) {
            return Double.NaN;
        }

        double predictedGf = predictedCenters[bestExpertOrdinal];
        double peakExposure = 0.0;
        for (oog.mega.saguaro.info.wave.BulletShadowUtil.WeightedGfInterval interval : intersection.exposedGfIntervals) {
            if (interval == null) {
                continue;
            }
            double gap = intervalDistance(predictedGf, interval.startGf, interval.endGf);
            double threat = interval.weight * gaussianScore(gap, PREDICTED_SHOT_SIGMA_GUESS_FACTOR);
            if (threat > peakExposure) {
                peakExposure = threat;
            }
        }
        if (!(peakExposure > 0.0)) {
            return 0.0;
        }

        double timeWeight = 1.0;
        long ticksUntilContact = Math.max(0L, intersection.firstContactTime - currentTime);
        if (ticksUntilContact > 0L) {
            timeWeight = 1.0 / (1.0 + WAVE_TIME_WEIGHT_SCALE * ticksUntilContact);
        }
        return timeWeight * Rules.getBulletDamage(wave.getFirepower()) * peakExposure;
    }

    private double distanceAdjustment(CandidatePath path) {
        if (path == null || path.trajectory == null || path.trajectory.length() == 0) {
            return 0.0;
        }
        EnemyInfo.PredictedPosition enemyAtEnd = predictEnemyPositionAt(path.startTime + path.trajectory.length() - 1L);
        if (enemyAtEnd == null) {
            return 0.0;
        }
        PhysicsUtil.PositionState endState = path.trajectory.stateAt(path.trajectory.length() - 1);
        double distance = Math.hypot(endState.x - enemyAtEnd.x, endState.y - enemyAtEnd.y);
        return DISTANCE_ADJUSTMENT_SCALE / Math.max(DISTANCE_ADJUSTMENT_MIN_DISTANCE, distance);
    }

    private static double intervalDistance(double value, double minValue, double maxValue) {
        if (value >= minValue && value <= maxValue) {
            return 0.0;
        }
        return value < minValue ? (minValue - value) : (value - maxValue);
    }

    private static double gaussianScore(double distance, double sigma) {
        if (!(sigma > 0.0)) {
            return distance <= 0.0 ? 1.0 : 0.0;
        }
        double normalized = distance / sigma;
        return Math.exp(-0.5 * normalized * normalized);
    }

    private double[] firstTickMovementInstruction(CandidatePath path, RobotSnapshot robotState) {
        if (path == null) {
            return new double[]{0.0, 0.0};
        }
        if (path.segmentLegs.isEmpty()) {
            return PhysicsUtil.computeMovementInstruction(
                    robotState.x,
                    robotState.y,
                    robotState.heading,
                    robotState.velocity,
                    path.firstTargetX,
                    path.firstTargetY,
                    PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                    PhysicsUtil.SteeringMode.DIRECT,
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight());
        }
        return PhysicsUtil.computeMovementInstruction(
                robotState.x,
                robotState.y,
                robotState.heading,
                robotState.velocity,
                path.segmentLegs.get(0).targetX,
                path.segmentLegs.get(0).targetY,
                PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                path.segmentLegs.get(0).steeringMode,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    private GunInstruction buildGunInstruction(CandidatePath path,
                                               RobotSnapshot robotState,
                                               int firstFiringTickOffset,
                                               double selectedFirePower) {
        double firePower = sanitizeFirePower(selectedFirePower, robotState.energy);
        if (path == null) {
            return new GunInstruction(0.0, 0.0);
        }

        int fireStateIndex = Math.min(firstFiringTickOffset, Math.max(0, path.trajectory.length() - 1));
        PhysicsUtil.PositionState fireState = path.trajectory.stateAt(fireStateIndex);
        long fireTime = path.startTime + Math.max(0, firstFiringTickOffset);
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyPositionAt(fireTime);
        if (enemyAtFireTime == null) {
            return new GunInstruction(0.0, 0.0);
        }

        double aimPower = firePower >= MIN_FIRE_POWER
                ? firePower
                : Math.min(BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER, robotState.energy);
        ShotSolution shot = gun.selectOptimalShotFromPosition(
                fireState.x,
                fireState.y,
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                aimPower,
                robotState.gunHeading,
                firstFiringTickOffset);
        if (shot == null || !Double.isFinite(shot.firingAngle)) {
            shot = gun.selectOptimalUnconstrainedShotFromPosition(
                    fireState.x,
                    fireState.y,
                    enemyAtFireTime.x,
                    enemyAtFireTime.y,
                    aimPower,
                    firstFiringTickOffset);
        }
        if (shot == null || !Double.isFinite(shot.firingAngle)) {
            shot = new ShotSolution(0.0, robotState.gunHeading);
        }

        double gunTurnAngle = MathUtils.normalizeAngle(shot.firingAngle - robotState.gunHeading);
        double fireNowPower = firstFiringTickOffset == 0 ? firePower : 0.0;
        return new GunInstruction(gunTurnAngle, fireNowPower);
    }

    private static double sanitizeFirePower(double firePower, double availableEnergy) {
        if (!(firePower >= MIN_FIRE_POWER) || !(availableEnergy >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.min(3.0, Math.min(firePower, availableEnergy));
    }

    private EnemyInfo.PredictedPosition predictEnemyPositionAt(long time) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        return enemy.predictPositionAtTime(
                time,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    private void invalidateSelectedPathStateIfWaveDangerChanged() {
        long currentRevision = info.getEnemyWaveDangerRevision();
        if (currentRevision == lastSelectedWaveDangerRevision) {
            return;
        }
        lastSelectedPath = null;
        lastSelectedFamilyPaths = new ArrayList<CandidatePath>();
        lastSelectedPathIntersections = new ArrayList<PathWaveIntersection>();
        lastSelectedWaveDangerRevision = currentRevision;
    }

    private static final class GunInstruction {
        final double gunTurnAngle;
        final double firePower;

        GunInstruction(double gunTurnAngle, double firePower) {
            this.gunTurnAngle = gunTurnAngle;
            this.firePower = firePower;
        }
    }
}
