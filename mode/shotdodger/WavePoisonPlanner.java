package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.DirectGunPlannerSupport;

final class WavePoisonPlanner {
    private static final double PREFIRE_REVERSE_DISTANCE = -1.0;
    private static final double STOP_CRAWL_DISTANCE = 0.1;
    private static final double DESIRED_ORBIT_DISTANCE = 250.0;
    private static final double MIN_SAFE_WALL_SMOOTHED_OFFSET = Math.toRadians(45.0);
    private static final double WALL_PROBE_DISTANCE = 80.0;
    private static final double ORBIT_WAYPOINT_DISTANCE = 140.0;
    private static final double MAX_OUTWARD_TARGET_BIAS = 90.0;
    private static final double SAFE_WALL_MARGIN = PhysicsUtil.WALL_MARGIN + 8.0;
    private static final int PREFIRE_REVERSE_TICKS = 1;
    private static final int GO_BURST_BUFFER_TICKS = 1;

    private Info info;
    private GunController gun;
    private long lastObservedEnemyFireTime = Long.MIN_VALUE;
    private int travelDirection = 0;
    private boolean movingAfterEnemyFire;
    private MovementPhase lastMovementPhase = MovementPhase.STOP;

    void init(Info info, GunController gun) {
        if (info == null || gun == null) {
            throw new IllegalArgumentException("WavePoisonPlanner requires non-null info and gun");
        }
        this.info = info;
        this.gun = gun;
        this.lastObservedEnemyFireTime = Long.MIN_VALUE;
        this.travelDirection = 0;
        this.movingAfterEnemyFire = false;
        this.lastMovementPhase = MovementPhase.STOP;
    }

    BattlePlan getBestPlan() {
        RobotSnapshot robotState = info.captureRobotSnapshot();
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            lastMovementPhase = MovementPhase.OPENING;
            return buildOpeningPlan(robotState);
        }

        long latestEnemyFireTime = latestObservedEnemyFireTime();
        if (latestEnemyFireTime > lastObservedEnemyFireTime) {
            lastObservedEnemyFireTime = latestEnemyFireTime;
            movingAfterEnemyFire = true;
        }

        EnemyInfo.PredictedPosition enemyAtNow = predictEnemyPositionAt(robotState.time);
        if (enemyAtNow == null) {
            lastMovementPhase = MovementPhase.OPENING;
            return buildOpeningPlan(robotState);
        }

        int resolvedTravelDirection = resolveTravelDirection(
                new PhysicsUtil.PositionState(robotState.x, robotState.y, robotState.heading, robotState.velocity),
                enemyAtNow,
                travelDirection);
        if (resolvedTravelDirection != 0) {
            travelDirection = resolvedTravelDirection;
        }

        MovementCommand movementCommand = buildMovementCommand(
                new PhysicsUtil.PositionState(robotState.x, robotState.y, robotState.heading, robotState.velocity),
                enemyAtNow,
                enemy.gunHeat,
                movingAfterEnemyFire,
                travelDirection);
        lastMovementPhase = movementCommand.phase;

        int firstFiringTickOffset = DirectGunPlannerSupport.firstFiringTickOffset(robotState.gunHeat);
        PhysicsUtil.PositionState fireState = predictFireState(
                robotState,
                firstFiringTickOffset,
                movingAfterEnemyFire,
                travelDirection);
        long fireTime = robotState.time + Math.max(0, firstFiringTickOffset);
        DirectGunPlannerSupport.GunInstruction gunInstruction = DirectGunPlannerSupport.buildGunInstruction(
                info,
                gun,
                fireState.x,
                fireState.y,
                robotState.gunHeading,
                robotState.energy,
                fireTime,
                firstFiringTickOffset,
                DirectGunPlannerSupport.selectFirePower(info, robotState));
        return new BattlePlan(
                movementCommand.moveDistance,
                movementCommand.turnAngle,
                gunInstruction.gunTurnAngle,
                gunInstruction.firePower);
    }

    String describeSkippedTurnDiagnostics() {
        return "movement=wavePoison phase=" + lastMovementPhase.name() + " dir=" + travelDirection;
    }

    private BattlePlan buildOpeningPlan(RobotSnapshot robotState) {
        double centerX = info.getBattlefieldWidth() * 0.5;
        double centerY = info.getBattlefieldHeight() * 0.5;
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                robotState.x,
                robotState.y,
                robotState.heading,
                robotState.velocity,
                centerX,
                centerY);
        return new BattlePlan(instruction[0], instruction[1], 0.0, 0.0);
    }

    private PhysicsUtil.PositionState predictFireState(RobotSnapshot robotState,
                                                       int ticksUntilFire,
                                                       boolean movingAfterEnemyFire,
                                                       int initialTravelDirection) {
        PhysicsUtil.PositionState state =
                new PhysicsUtil.PositionState(robotState.x, robotState.y, robotState.heading, robotState.velocity);
        EnemyInfo enemy = info.getEnemy();
        if (ticksUntilFire <= 0 || enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return state;
        }

        boolean simulatedMovingAfterEnemyFire = movingAfterEnemyFire;
        int simulatedTravelDirection = initialTravelDirection;
        double gunCoolingRate = robotState.gunCoolingRate;
        for (int tickOffset = 0; tickOffset < ticksUntilFire; tickOffset++) {
            long time = robotState.time + tickOffset;
            EnemyInfo.PredictedPosition enemyAtTime = predictEnemyPositionAt(time);
            if (enemyAtTime == null) {
                break;
            }
            double projectedEnemyGunHeat = Math.max(0.0, enemy.gunHeat - gunCoolingRate * tickOffset);
            MovementCommand movementCommand = buildMovementCommand(
                    state,
                    enemyAtTime,
                    projectedEnemyGunHeat,
                    simulatedMovingAfterEnemyFire,
                    simulatedTravelDirection);
            state = PhysicsUtil.calculateNextTick(
                    state.x,
                    state.y,
                    state.heading,
                    state.velocity,
                    movementCommand.moveDistance,
                    movementCommand.turnAngle,
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight());
        }
        return state;
    }

    private MovementCommand buildMovementCommand(PhysicsUtil.PositionState state,
                                                 EnemyInfo.PredictedPosition enemyAtTime,
                                                 double enemyGunHeat,
                                                 boolean movingAfterEnemyFire,
                                                 int preferredDirection) {
        int resolvedDirection = resolveTravelDirection(state, enemyAtTime, preferredDirection);
        double desiredHeading = wallSmoothedOrbitHeading(state, enemyAtTime, resolvedDirection);
        int ticksUntilEnemyReady = ticksUntilGunReady(enemyGunHeat);
        int brakingTicks = brakingTicksForVelocity(state.velocity);
        if (ticksUntilEnemyReady == PREFIRE_REVERSE_TICKS) {
            return createMovementCommand(state.heading, desiredHeading, PREFIRE_REVERSE_DISTANCE, MovementPhase.PREFIRE_REVERSE);
        }
        if (movingAfterEnemyFire && ticksUntilEnemyReady > brakingTicks + GO_BURST_BUFFER_TICKS) {
            return createGoMovementCommand(state, enemyAtTime, resolvedDirection);
        }
        return createMovementCommandWithTravelSign(
                state.heading,
                desiredHeading,
                STOP_CRAWL_DISTANCE,
                resolvedDirection,
                MovementPhase.STOP);
    }

    private static MovementCommand createMovementCommand(double currentHeading,
                                                         double desiredHeading,
                                                         double commandDistance,
                                                         MovementPhase phase) {
        double turnAngle = MathUtils.normalizeAngle(desiredHeading - currentHeading);
        double moveDistance = commandDistance;
        if (commandDistance != 0.0 && Math.abs(turnAngle) > Math.PI * 0.5) {
            turnAngle = MathUtils.normalizeAngle(turnAngle + Math.PI);
            moveDistance = -commandDistance;
        }
        return new MovementCommand(moveDistance, turnAngle, phase);
    }

    private static MovementCommand createMovementCommandWithTravelSign(double currentHeading,
                                                                       double desiredTravelHeading,
                                                                       double commandDistance,
                                                                       int travelSign,
                                                                       MovementPhase phase) {
        double signedDistance = Math.abs(commandDistance) * (travelSign >= 0 ? 1.0 : -1.0);
        double bodyHeading = travelSign >= 0
                ? desiredTravelHeading
                : MathUtils.normalizeAngle(desiredTravelHeading + Math.PI);
        double turnAngle = MathUtils.normalizeAngle(bodyHeading - currentHeading);
        return new MovementCommand(signedDistance, turnAngle, phase);
    }

    private int resolveTravelDirection(PhysicsUtil.PositionState state,
                                       EnemyInfo.PredictedPosition enemyAtTime,
                                       int preferredDirection) {
        if (preferredDirection == 0) {
            double positiveScore = probeDirectionScore(state, enemyAtTime, 1);
            double negativeScore = probeDirectionScore(state, enemyAtTime, -1);
            return negativeScore > positiveScore + 1e-9 ? -1 : 1;
        }

        if (!wallSmoothingApproachesTooSharply(state, enemyAtTime, preferredDirection)) {
            return preferredDirection;
        }

        return !wallSmoothingApproachesTooSharply(state, enemyAtTime, -preferredDirection)
                ? -preferredDirection
                : preferredDirection;
    }

    private double desiredTravelHeading(PhysicsUtil.PositionState state,
                                        EnemyInfo.PredictedPosition enemyAtTime,
                                        int direction) {
        double absoluteBearing = Math.atan2(enemyAtTime.x - state.x, enemyAtTime.y - state.y);
        return MathUtils.normalizeAngle(absoluteBearing + direction * (Math.PI * 0.5));
    }

    private double probeDirectionScore(PhysicsUtil.PositionState state,
                                       EnemyInfo.PredictedPosition enemyAtTime,
                                       int direction) {
        double[] orbitTarget = orbitTarget(state, enemyAtTime, direction, WALL_PROBE_DISTANCE);
        double probeX = orbitTarget[0];
        double probeY = orbitTarget[1];
        return distanceToNearestWall(probeX, probeY);
    }

    private MovementCommand createGoMovementCommand(PhysicsUtil.PositionState state,
                                                    EnemyInfo.PredictedPosition enemyAtTime,
                                                    int direction) {
        double[] orbitTarget = orbitTarget(state, enemyAtTime, direction, ORBIT_WAYPOINT_DISTANCE);
        double[] instruction = PhysicsUtil.computeMovementInstruction(
                state.x,
                state.y,
                state.heading,
                state.velocity,
                orbitTarget[0],
                orbitTarget[1],
                PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                steeringModeForDirection(direction),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return new MovementCommand(instruction[0], instruction[1], MovementPhase.GO);
    }

    private double wallSmoothedOrbitHeading(PhysicsUtil.PositionState state,
                                            EnemyInfo.PredictedPosition enemyAtTime,
                                            int direction) {
        double[] orbitTarget = orbitTarget(state, enemyAtTime, direction, ORBIT_WAYPOINT_DISTANCE);
        double smoothedHeading = PhysicsUtil.computeTravelAngle(
                state.x,
                state.y,
                orbitTarget[0],
                orbitTarget[1],
                steeringModeForDirection(direction),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        if (Double.isFinite(smoothedHeading)) {
            return smoothedHeading;
        }
        return desiredTravelHeading(state, enemyAtTime, direction);
    }

    private boolean wallSmoothingApproachesTooSharply(PhysicsUtil.PositionState state,
                                                      EnemyInfo.PredictedPosition enemyAtTime,
                                                      int direction) {
        double absoluteBearing = Math.atan2(enemyAtTime.x - state.x, enemyAtTime.y - state.y);
        double rawHeading = desiredTravelHeading(state, enemyAtTime, direction);
        double smoothedHeading = wallSmoothedOrbitHeading(state, enemyAtTime, direction);
        double rawOffset = Math.abs(MathUtils.normalizeAngle(rawHeading - absoluteBearing));
        double smoothedOffset = Math.abs(MathUtils.normalizeAngle(smoothedHeading - absoluteBearing));
        return smoothedOffset < MIN_SAFE_WALL_SMOOTHED_OFFSET && smoothedOffset < rawOffset - 1e-9;
    }

    private static PhysicsUtil.SteeringMode steeringModeForDirection(int direction) {
        return direction >= 0
                ? PhysicsUtil.SteeringMode.WALL_SMOOTHED_CCW
                : PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW;
    }

    private double[] orbitTarget(PhysicsUtil.PositionState state,
                                 EnemyInfo.PredictedPosition enemyAtTime,
                                 int direction,
                                 double stepDistance) {
        double heading = desiredTravelHeading(state, enemyAtTime, direction);
        double absoluteBearing = Math.atan2(enemyAtTime.x - state.x, enemyAtTime.y - state.y);
        double distance = Math.hypot(enemyAtTime.x - state.x, enemyAtTime.y - state.y);
        double outwardHeading = MathUtils.normalizeAngle(absoluteBearing + Math.PI);
        double outwardDistance = clamp(DESIRED_ORBIT_DISTANCE - distance, 0.0, MAX_OUTWARD_TARGET_BIAS);
        return new double[] {
                state.x + Math.sin(heading) * stepDistance + Math.sin(outwardHeading) * outwardDistance,
                state.y + Math.cos(heading) * stepDistance + Math.cos(outwardHeading) * outwardDistance
        };
    }

    private double distanceToNearestWall(double x, double y) {
        double battlefieldWidth = info.getBattlefieldWidth();
        double battlefieldHeight = info.getBattlefieldHeight();
        return Math.min(
                Math.min(x - SAFE_WALL_MARGIN, battlefieldWidth - SAFE_WALL_MARGIN - x),
                Math.min(y - SAFE_WALL_MARGIN, battlefieldHeight - SAFE_WALL_MARGIN - y));
    }

    private int ticksUntilGunReady(double gunHeat) {
        double gunCoolingRate = info.getRobot().getGunCoolingRate();
        if (gunHeat <= 0.0 || !(gunCoolingRate > 0.0)) {
            return 0;
        }
        return (int) Math.ceil(gunHeat / gunCoolingRate);
    }

    private static int brakingTicksForVelocity(double velocity) {
        return Math.max(2, (int) Math.ceil(Math.abs(velocity) / 2.0) + 1);
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

    private long latestObservedEnemyFireTime() {
        long latestFireTime = Long.MIN_VALUE;
        for (Wave wave : info.getEnemyWaves()) {
            if (wave == null || !wave.isEnemy || wave.isVirtual) {
                continue;
            }
            if (wave.fireTime > latestFireTime) {
                latestFireTime = wave.fireTime;
            }
        }
        return latestFireTime;
    }

    private static double clamp(double value, double minValue, double maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static final class MovementCommand {
        final double moveDistance;
        final double turnAngle;
        final MovementPhase phase;

        MovementCommand(double moveDistance, double turnAngle, MovementPhase phase) {
            this.moveDistance = moveDistance;
            this.turnAngle = turnAngle;
            this.phase = phase;
        }
    }

    private enum MovementPhase {
        OPENING,
        STOP,
        GO,
        PREFIRE_REVERSE
    }
}
