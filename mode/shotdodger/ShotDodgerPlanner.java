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
    private static final double FIXED_FIRE_POWER = 2.0;
    private static final double MIN_FIRE_POWER = 0.1;
    private static final double OPPONENT_DISTANCE_DANGER_WEIGHT = 2500.0;

    private Info info;
    private MovementController movement;
    private GunController gun;
    private ShotDodgerObservationProfile observationProfile;
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
        GunInstruction gunInstruction = buildGunInstruction(
                bestPath,
                robotState,
                firstFiringTickOffset,
                FIXED_FIRE_POWER);
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
            if (bestPath == null || cost < bestCost - 1e-9) {
                bestPath = path;
                bestCost = cost;
            }
        }
        return bestPath;
    }

    private double scorePath(CandidatePath path, List<PathWaveIntersection> intersections) {
        double predictedDanger = 0.0;
        int bestExpertOrdinal = observationProfile.currentBestMovementExpertId().ordinal();

        if (intersections != null) {
            for (PathWaveIntersection intersection : intersections) {
                double waveDanger = scoreIntersection(intersection, bestExpertOrdinal);
                if (!Double.isFinite(waveDanger)) {
                    return Double.POSITIVE_INFINITY;
                }
                predictedDanger += waveDanger;
            }
        }

        double opponentDistanceDanger = opponentDistanceDanger(path);
        if (!Double.isFinite(opponentDistanceDanger)) {
            return Double.POSITIVE_INFINITY;
        }
        return predictedDanger + opponentDistanceDanger;
    }

    private double scoreIntersection(PathWaveIntersection intersection,
                                     int bestExpertOrdinal) {
        if (intersection == null
                || intersection.wave == null
                || intersection.exposedGfIntervals == null
                || intersection.exposedGfIntervals.isEmpty()) {
            return 0.0;
        }
        Wave wave = intersection.wave;
        double[] predictedCenters = wave.fireTimeRenderGfMarkers;
        if (predictedCenters == null
                || bestExpertOrdinal < 0
                || bestExpertOrdinal >= predictedCenters.length
                || !Double.isFinite(predictedCenters[bestExpertOrdinal])) {
            return 0.0;
        }

        double predictedGf = predictedCenters[bestExpertOrdinal];
        double minAngularGap = Double.POSITIVE_INFINITY;
        for (oog.mega.saguaro.info.wave.BulletShadowUtil.WeightedGfInterval interval : intersection.exposedGfIntervals) {
            if (interval == null) {
                continue;
            }
            double gapGf = intervalDistance(predictedGf, interval.startGf, interval.endGf);
            if (!(gapGf > 0.0)) {
                return Double.POSITIVE_INFINITY;
            }
            double angularGap = Math.abs(gapGf * intersection.mea);
            if (angularGap < minAngularGap) {
                minAngularGap = angularGap;
            }
        }
        if (!Double.isFinite(minAngularGap)) {
            return 0.0;
        }
        return Rules.getBulletDamage(wave.getFirepower()) / square(minAngularGap);
    }

    private double opponentDistanceDanger(CandidatePath path) {
        if (path == null || path.trajectory == null || path.trajectory.length() == 0) {
            return 0.0;
        }
        double minDistance = Double.POSITIVE_INFINITY;
        int pathLength = path.trajectory.length();
        for (int i = 0; i < pathLength; i++) {
            EnemyInfo.PredictedPosition enemyAtTime = predictEnemyPositionAt(path.startTime + i);
            if (enemyAtTime == null) {
                continue;
            }
            PhysicsUtil.PositionState state = path.trajectory.stateAt(i);
            double distance = Math.hypot(state.x - enemyAtTime.x, state.y - enemyAtTime.y);
            if (distance < minDistance) {
                minDistance = distance;
            }
        }
        if (!Double.isFinite(minDistance)) {
            return 0.0;
        }
        if (!(minDistance > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        return OPPONENT_DISTANCE_DANGER_WEIGHT / square(minDistance);
    }

    private static double intervalDistance(double value, double minValue, double maxValue) {
        if (value >= minValue && value <= maxValue) {
            return 0.0;
        }
        return value < minValue ? (minValue - value) : (value - maxValue);
    }

    private static double square(double value) {
        return value * value;
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
