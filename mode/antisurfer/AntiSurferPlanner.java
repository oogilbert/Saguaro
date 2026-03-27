package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.MovementController;
import oog.mega.saguaro.movement.PathGenerationContext;
import oog.mega.saguaro.movement.PathWaveIntersection;

final class AntiSurferPlanner {
    private static final double FIXED_FIRE_POWER = 2.0;
    private static final double MIN_FIRE_POWER = 0.1;

    private Info info;
    private MovementController movement;
    private GunController gun;
    private CandidatePath lastSelectedPath;
    private List<CandidatePath> lastSelectedFamilyPaths = new ArrayList<CandidatePath>();
    private List<PathWaveIntersection> lastSelectedPathIntersections = new ArrayList<PathWaveIntersection>();
    private long lastSelectedWaveDangerRevision;

    void init(Info info, MovementController movement, GunController gun) {
        if (info == null || movement == null || gun == null) {
            throw new IllegalArgumentException("AntiSurferPlanner requires non-null info, movement, and gun");
        }
        this.info = info;
        this.movement = movement;
        this.gun = gun;
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
                : new ArrayList<PathWaveIntersection>();
        lastSelectedWaveDangerRevision = info.getEnemyWaveDangerRevision();

        double[] movementInstruction = firstTickMovementInstruction(bestPath, robotState);
        GunInstruction gunInstruction = buildGunInstruction(bestPath, robotState, firstFiringTickOffset);
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
            double cost = path.totalDanger + path.wallHitDamage;
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
        if (candidate.totalDanger < incumbent.totalDanger - 1e-9) {
            return true;
        }
        if (candidate.totalDanger > incumbent.totalDanger + 1e-9) {
            return false;
        }
        return candidate.wallHitDamage < incumbent.wallHitDamage - 1e-9;
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
                                               int firstFiringTickOffset) {
        double firePower = selectFirePower(robotState.energy);
        if (!(firePower >= MIN_FIRE_POWER) || path == null) {
            return new GunInstruction(0.0, 0.0);
        }

        PhysicsUtil.PositionState fireState = path.trajectory.stateAt(firstFiringTickOffset);
        long fireTime = path.startTime + Math.max(0, firstFiringTickOffset);
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyPositionAt(fireTime);
        if (enemyAtFireTime == null) {
            return new GunInstruction(0.0, 0.0);
        }

        ShotSolution shot = firstFiringTickOffset == 0
                ? gun.selectOptimalShotFromPosition(
                        fireState.x,
                        fireState.y,
                        enemyAtFireTime.x,
                        enemyAtFireTime.y,
                        firePower,
                        robotState.gunHeading,
                        0)
                : gun.selectOptimalUnconstrainedShotFromPosition(
                        fireState.x,
                        fireState.y,
                        enemyAtFireTime.x,
                        enemyAtFireTime.y,
                        firePower,
                        0);
        if (shot == null || !Double.isFinite(shot.firingAngle)) {
            return new GunInstruction(0.0, 0.0);
        }

        double gunTurnAngle = MathUtils.normalizeAngle(shot.firingAngle - robotState.gunHeading);
        double fireNowPower = firstFiringTickOffset == 0 ? firePower : 0.0;
        return new GunInstruction(gunTurnAngle, fireNowPower);
    }

    private static double selectFirePower(double availableEnergy) {
        if (!(availableEnergy >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.min(FIXED_FIRE_POWER, availableEnergy);
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
