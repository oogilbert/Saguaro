package oog.mega.saguaro.mode.shield;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.NoOpLearningProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.info.persistence.OpponentDataSet;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.render.PathOverlay;
import oog.mega.saguaro.render.RenderState;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.RobotDeathEvent;
import robocode.Rules;
import robocode.ScannedRobotEvent;
import robocode.util.Utils;

/**
 * BulletShielding mode that also tries to sneak in aggressive shots whenever we have spare gun heat.
 * 
 * The BulletShielding geometry/plan generation is based on BeepBoop, and the way it learns opponent targeting is based on EnergyDome,
 * so big thanks to Kev and Skilgannon for open sourcing their bots
 */
public final class BulletShieldMode implements BattleMode {
    private static final int BASE_OPTIONS = 4;
    private static final int OPTION_GROUPS = 3;
    private static final int OPTIONS = BASE_OPTIONS * OPTION_GROUPS;
    private static final double BODY_HALF_WIDTH = 18.0;
    private static final double BODY_HALF_DIAGONAL = 18.1 * Math.sqrt(2.0);
    private static final double WIGGLE_SIZE = 0.1;
    private static final double OPENING_ANTI_GRAVITY_COMMAND_DISTANCE = 1000.0;
    private static final double MODEL_SCORE_MATCH_EPSILON = 1e-5;
    private static final double WAVE_MATCH_POWER_TOLERANCE = 1e-3;
    private static final double SHARED_WAVE_ORIGIN_TOLERANCE = 0.6;
    private static final Color SHIELD_COLLISION_PATH_COLOR = new Color(255, 244, 128, 220);
    private static final Color ENEMY_COLLISION_PATH_COLOR = new Color(255, 118, 118, 220);
    private static final Color SHIELD_COLLISION_CROSSHAIR_COLOR = new Color(255, 255, 255, 220);
    private static final float SHIELD_COLLISION_PATH_STROKE_WIDTH = 2.2f;
    private static final float SHIELD_COLLISION_CROSSHAIR_STROKE_WIDTH = 1.8f;
    private static final int SHIELD_COLLISION_CROSSHAIR_HALF_LENGTH = 6;
    private static final Color AGGRESSIVE_SHOT_PATH_COLOR = new Color(120, 214, 255, 220);
    private static final Color AGGRESSIVE_SHOT_BOX_COLOR = new Color(255, 255, 255, 220);
    private static final float AGGRESSIVE_SHOT_BOX_STROKE_WIDTH = 1.8f;
    private static final int AGGRESSIVE_SHOT_BOX_HALF_LENGTH = 18;
    private static final int PERSISTED_BOOTSTRAP_SECTION_VERSION = 1;
    private static final int PERSISTED_BOOTSTRAP_BYTES = 9;
    private static final ShieldBootstrapState[] BOOTSTRAP_STATES = createBootstrapStates();

    private final Deque<Snapshot> myHistory = new ArrayDeque<Snapshot>();
    private final Deque<Snapshot> enemyHistory = new ArrayDeque<Snapshot>();
    private final List<EnemyWave> enemyWaves = new ArrayList<EnemyWave>();
    private final List<ShieldCollisionPath> firedShieldCollisionPaths = new ArrayList<ShieldCollisionPath>();
    private final List<AggressiveShotPath> firedAggressiveShotPaths = new ArrayList<AggressiveShotPath>();
    private final Map<Wave, EnemyWave> enemyWavesBySharedWave = new HashMap<Wave, EnemyWave>();

    private final RoundOutcomeProfile roundOutcomeProfile = NoOpLearningProfile.INSTANCE;
    private final ModeId modeId;
    private final boolean allowOpeningCenterReposition;
    private final Class<? extends OpponentDataSet> shieldDataSetType;
    private final ShieldBootstrapState bootstrapState;
    private Info info;
    private Saguaro robot;
    private GunController gun;
    private BattleDataStore dataStore;
    private ScannedRobotEvent lastScanEvent;
    private Snapshot latestEnemy;
    private ShieldPlan activePlan;
    private double lateralDirection = 1.0;
    private boolean enemyHasMoved;
    private int enemyShots;
    private long nextWaveId;
    private long estimatedEnemyNextFireTime = Long.MIN_VALUE;
    private long lastDetectedEnemyShotTime = Long.MIN_VALUE;
    private double lastDetectedEnemyBulletPower = 2.0;
    private long pendingAggressiveShotTime = Long.MIN_VALUE;

    public BulletShieldMode(ModeId modeId,
                            boolean allowOpeningCenterReposition,
                            Class<? extends OpponentDataSet> shieldDataSetType) {
        validateShieldModeId(modeId);
        if (shieldDataSetType == null) {
            throw new IllegalArgumentException("BulletShieldMode requires a non-null dataset type");
        }
        this.modeId = modeId;
        this.allowOpeningCenterReposition = allowOpeningCenterReposition;
        this.shieldDataSetType = shieldDataSetType;
        this.bootstrapState = stateFor(modeId);
    }

    @Override
    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfile;
    }

    @Override
    public ModeObservationPolicy getObservationPolicy() {
        return ModeObservationPolicy.TARGETING_ONLY;
    }

    @Override
    public void init(Info info, BattleServices services) {
        if (info == null) {
            throw new IllegalArgumentException("BulletShieldMode requires non-null info");
        }
        this.info = info;
        this.robot = info.getRobot();
        if (this.robot == null) {
            throw new IllegalArgumentException("BulletShieldMode requires non-null robot");
        }
        this.gun = services.gun();
        if (this.gun == null) {
            throw new IllegalArgumentException("BulletShieldMode requires non-null gun service");
        }
        this.dataStore = services.dataStore();
        if (this.dataStore == null) {
            throw new IllegalArgumentException("BulletShieldMode requires non-null data store");
        }
        this.lastScanEvent = null;
        this.latestEnemy = null;
        this.activePlan = null;
        this.lateralDirection = 1.0;
        this.enemyHasMoved = false;
        this.enemyShots = 0;
        this.nextWaveId = 0L;
        this.estimatedEnemyNextFireTime = Long.MIN_VALUE;
        this.lastDetectedEnemyShotTime = Long.MIN_VALUE;
        this.lastDetectedEnemyBulletPower = 2.0;
        this.pendingAggressiveShotTime = Long.MIN_VALUE;
        this.myHistory.clear();
        this.enemyHistory.clear();
        this.enemyWaves.clear();
        this.firedShieldCollisionPaths.clear();
        this.firedAggressiveShotPaths.clear();
        this.enemyWavesBySharedWave.clear();
    }

    @Override
    public void onBattleEnded(Saguaro robot) {
        if (dataStore != null) {
            dataStore.requestDataSetSave(shieldDataSetType);
        }
    }

    @Override
    public BattlePlan getPlan() {
        return tick();
    }

    @Override
    public void onFireResult(Bullet bullet, BattlePlan plan) {
        if (plan == null) {
            return;
        }
        Snapshot myNow = captureSelf();
        if (activePlan != null
                && myNow.time == activePlan.fireTime
                && !activePlan.fireIssued
                && plan.firePower >= 0.1) {
            if (bullet != null) {
                activePlan.fireIssued = true;
                activePlan.wave.shieldAttempted = true;
                recordFiredShieldCollisionPath(activePlan, bullet);
            }
        } else if (bullet != null && pendingAggressiveShotTime == myNow.time) {
            recordFiredAggressiveShotPath(myNow, bullet);
        }
        pendingAggressiveShotTime = Long.MIN_VALUE;
    }

    @Override
    public RenderState getRenderState() {
        return new RenderState(buildShieldModePathOverlays(), false);
    }

    private void recordFiredShieldCollisionPath(ShieldPlan plan, Bullet bullet) {
        if (plan == null || plan.wave == null || bullet == null) {
            return;
        }
        if (!Double.isFinite(plan.collisionX) || !Double.isFinite(plan.collisionY)) {
            return;
        }
        BulletLinePath myPath = buildBulletLinePath(
                plan.fireTime,
                bullet.getX(),
                bullet.getY(),
                bullet.getVelocity(),
                plan.interceptTime,
                plan.collisionX,
                plan.collisionY);
        BulletLinePath enemyPath = buildBulletLinePath(
                plan.wave.fireTime,
                plan.wave.originX,
                plan.wave.originY,
                plan.wave.speed,
                plan.interceptTime,
                plan.collisionX,
                plan.collisionY);
        if (myPath == null || enemyPath == null) {
            return;
        }
        firedShieldCollisionPaths.add(new ShieldCollisionPath(myPath, enemyPath, plan.interceptTime));
    }

    private List<PathOverlay> buildShieldModePathOverlays() {
        List<PathOverlay> overlays = new ArrayList<PathOverlay>();
        if (robot == null) {
            return overlays;
        }
        appendShieldCollisionPathOverlays(overlays);
        appendAggressiveShotPathOverlays(overlays);
        return overlays;
    }

    private void appendShieldCollisionPathOverlays(List<PathOverlay> overlays) {
        if (overlays == null || firedShieldCollisionPaths.isEmpty() || robot == null) {
            return;
        }
        long currentTime = robot.getTime();
        Iterator<ShieldCollisionPath> iterator = firedShieldCollisionPaths.iterator();
        while (iterator.hasNext()) {
            ShieldCollisionPath collisionPath = iterator.next();
            if (currentTime > collisionPath.interceptTime) {
                iterator.remove();
                continue;
            }
            PathOverlay enemyOverlay =
                    buildShieldCollisionOverlay(collisionPath.enemyPath, currentTime, ENEMY_COLLISION_PATH_COLOR, null);
            if (enemyOverlay != null) {
                overlays.add(enemyOverlay);
            }
            PathOverlay myOverlay = buildShieldCollisionOverlay(
                    collisionPath.myPath,
                    currentTime,
                    SHIELD_COLLISION_PATH_COLOR,
                    new PathOverlay.Marker(
                            collisionPath.myPath.endX,
                            collisionPath.myPath.endY,
                            0.0,
                            SHIELD_COLLISION_CROSSHAIR_COLOR,
                            SHIELD_COLLISION_CROSSHAIR_STROKE_WIDTH,
                            SHIELD_COLLISION_CROSSHAIR_HALF_LENGTH,
                            PathOverlay.Marker.Style.CROSSHAIR));
            if (myOverlay != null) {
                overlays.add(myOverlay);
            }
        }
    }

    private void recordFiredAggressiveShotPath(Snapshot myNow, Bullet bullet) {
        if (myNow == null || bullet == null || latestEnemy == null || info == null) {
            return;
        }
        double targetDistance = Point2D.distance(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y);
        if (!(targetDistance > 1e-9)) {
            return;
        }
        Point2D.Double projectedImpact = projectInsideBattlefieldAlongAngle(
                bullet.getX(),
                bullet.getY(),
                bullet.getHeadingRadians(),
                targetDistance,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        BulletLinePath shotPath = buildBulletLinePath(
                myNow.time,
                bullet.getX(),
                bullet.getY(),
                bullet.getVelocity(),
                estimateImpactTime(myNow.time, bullet.getX(), bullet.getY(), bullet.getVelocity(), projectedImpact),
                projectedImpact.x,
                projectedImpact.y);
        if (shotPath == null) {
            return;
        }
        firedAggressiveShotPaths.add(new AggressiveShotPath(shotPath));
    }

    private void appendAggressiveShotPathOverlays(List<PathOverlay> overlays) {
        if (overlays == null || firedAggressiveShotPaths.isEmpty() || robot == null) {
            return;
        }
        long currentTime = robot.getTime();
        Iterator<AggressiveShotPath> iterator = firedAggressiveShotPaths.iterator();
        while (iterator.hasNext()) {
            AggressiveShotPath shotPath = iterator.next();
            if (currentTime > shotPath.bulletPath.interceptTime) {
                iterator.remove();
                continue;
            }
            PathOverlay overlay = buildShieldCollisionOverlay(
                    shotPath.bulletPath,
                    currentTime,
                    AGGRESSIVE_SHOT_PATH_COLOR,
                    new PathOverlay.Marker(
                            shotPath.bulletPath.endX,
                            shotPath.bulletPath.endY,
                            0.0,
                            AGGRESSIVE_SHOT_BOX_COLOR,
                            AGGRESSIVE_SHOT_BOX_STROKE_WIDTH,
                            AGGRESSIVE_SHOT_BOX_HALF_LENGTH,
                            PathOverlay.Marker.Style.BOX));
            if (overlay != null) {
                overlays.add(overlay);
            }
        }
    }

    private PathOverlay buildShieldCollisionOverlay(BulletLinePath linePath,
                                                    long currentTime,
                                                    Color color,
                                                    PathOverlay.Marker marker) {
        if (linePath == null || color == null) {
            return null;
        }
        Point2D.Double currentPosition = currentBulletLinePosition(linePath, currentTime);
        PhysicsUtil.Trajectory trajectory = new PhysicsUtil.Trajectory(new PhysicsUtil.PositionState[] {
                new PhysicsUtil.PositionState(currentPosition.x, currentPosition.y, 0.0, 0.0),
                new PhysicsUtil.PositionState(linePath.endX, linePath.endY, 0.0, 0.0)
        });
        return new PathOverlay(
                trajectory,
                currentTime,
                null,
                color,
                color,
                SHIELD_COLLISION_PATH_STROKE_WIDTH,
                false,
                marker);
    }

    private static BulletLinePath buildBulletLinePath(long startTime,
                                                      double startX,
                                                      double startY,
                                                      double speed,
                                                      long interceptTime,
                                                      double endX,
                                                      double endY) {
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || !Double.isFinite(endX) || !Double.isFinite(endY)) {
            return null;
        }
        if (!(speed > 0.0) || interceptTime < startTime) {
            return null;
        }
        double pathLength = Point2D.distance(startX, startY, endX, endY);
        if (!(pathLength > 1e-9)) {
            return null;
        }
        return new BulletLinePath(
                startTime,
                interceptTime,
                startX,
                startY,
                endX,
                endY,
                speed,
                (endX - startX) / pathLength,
                (endY - startY) / pathLength,
                pathLength);
    }

    private static long estimateImpactTime(long fireTime,
                                           double startX,
                                           double startY,
                                           double bulletSpeed,
                                           Point2D.Double impactPoint) {
        if (!(bulletSpeed > 0.0) || impactPoint == null) {
            return fireTime;
        }
        double pathLength = Point2D.distance(startX, startY, impactPoint.x, impactPoint.y);
        return fireTime + Math.max(1L, (long) Math.ceil(pathLength / bulletSpeed));
    }

    private static Point2D.Double projectInsideBattlefieldAlongAngle(double startX,
                                                                     double startY,
                                                                     double angle,
                                                                     double distance,
                                                                     double battlefieldWidth,
                                                                     double battlefieldHeight) {
        double directionX = Math.sin(angle);
        double directionY = Math.cos(angle);
        double maxDistance = Math.max(0.0, distance);
        double minX = PhysicsUtil.WALL_MARGIN;
        double minY = PhysicsUtil.WALL_MARGIN;
        double maxX = battlefieldWidth - PhysicsUtil.WALL_MARGIN;
        double maxY = battlefieldHeight - PhysicsUtil.WALL_MARGIN;

        if (directionX > 1e-9) {
            maxDistance = Math.min(maxDistance, (maxX - startX) / directionX);
        } else if (directionX < -1e-9) {
            maxDistance = Math.min(maxDistance, (minX - startX) / directionX);
        }

        if (directionY > 1e-9) {
            maxDistance = Math.min(maxDistance, (maxY - startY) / directionY);
        } else if (directionY < -1e-9) {
            maxDistance = Math.min(maxDistance, (minY - startY) / directionY);
        }

        double clampedDistance = Math.max(0.0, maxDistance);
        return new Point2D.Double(
                startX + directionX * clampedDistance,
                startY + directionY * clampedDistance);
    }

    private static Point2D.Double currentBulletLinePosition(BulletLinePath linePath, long currentTime) {
        long elapsedTicks = Math.max(0L, currentTime - linePath.startTime);
        double traveledDistance = Math.min(linePath.pathLength, elapsedTicks * linePath.speed);
        return new Point2D.Double(
                linePath.startX + linePath.unitX * traveledDistance,
                linePath.startY + linePath.unitY * traveledDistance);
    }

    @Override
    public void applyColors(Saguaro robot) {
        if (allowOpeningCenterReposition) {
            robot.setBodyColor(new Color(18, 116, 140));
            robot.setGunColor(new Color(12, 87, 106));
            robot.setRadarColor(new Color(34, 148, 172));
            robot.setBulletColor(new Color(70, 188, 214));
            robot.setScanColor(new Color(40, 166, 190));
            return;
        }
        robot.setBodyColor(new Color(4, 137, 80));
        robot.setGunColor(new Color(2, 100, 58));
        robot.setRadarColor(new Color(20, 170, 100));
        robot.setBulletColor(new Color(60, 200, 130));
        robot.setScanColor(new Color(30, 180, 110));
    }

    private BattlePlan tick() {
        Snapshot myNow = captureSelf();
        if (lastScanEvent != null && lastScanEvent.getTime() == myNow.time) {
            processScan(lastScanEvent, myNow);
        }

        updateEnemyWaves(myNow);

        if (activePlan != null && !isPlanAlive(activePlan, myNow)) {
            activePlan.wave.shieldAttempted = true;
            activePlan = null;
        }
        EnemyWave nextWave = null;
        if (activePlan == null) {
            nextWave = selectWaveToShield(myNow);
            if (nextWave != null) {
                ShieldPlan candidate = buildShieldPlan(nextWave, myNow);
                if (candidate != null) {
                    activePlan = candidate;
                } else {
                    BattlePlan directAttack = tryFireDirectAttack(myNow);
                    if (directAttack != null) {
                        return directAttack;
                    }
                }
            }
        }

        if (activePlan != null) {
            return executeShieldPlan(activePlan, myNow);
        }
        BattlePlan openingMove = tryOpeningCenterReposition(myNow);
        if (openingMove != null) {
            return openingMove;
        }
        if (nextWave == null) {
            BattlePlan finisher = tryFireFinisher(myNow);
            if (finisher != null) {
                return finisher;
            }
        }
        BattlePlan directAttack = tryFireDirectAttack(myNow);
        if (directAttack != null) {
            return directAttack;
        }
        return holdShieldStance(myNow);
    }

    private void processScan(ScannedRobotEvent event, Snapshot myNow) {
        EnemyInfo enemy = info != null ? info.getEnemy() : null;
        if (enemy == null || !enemy.alive || !enemy.seenThisRound || enemy.lastScanTime != event.getTime()) {
            throw new IllegalStateException("Shield mode requires synchronized enemy scan state from Info");
        }
        double absoluteBearing = normalize(robot.getHeadingRadians() + event.getBearingRadians());
        Point2D.Double enemyLocation = project(myNow.x, myNow.y, absoluteBearing, event.getDistance());
        Snapshot enemyNow = new Snapshot(
                enemyLocation.x,
                enemyLocation.y,
                normalize(event.getHeadingRadians()),
                event.getVelocity(),
                enemy.energy,
                absoluteBearing,
                myNow.time,
                Double.NaN,
                Double.NaN,
                Double.NaN);
        latestEnemy = enemyNow;

        double latVel = myNow.velocity * Math.sin(event.getBearingRadians());
        if (latVel < -1e-6) {
            lateralDirection = -1.0;
        } else if (latVel > 1e-6) {
            lateralDirection = 1.0;
        }
        if (Math.abs(enemyNow.velocity) > 0.01) {
            enemyHasMoved = true;
        }

        pushFront(myHistory, myNow);
        pushFront(enemyHistory, enemyNow);
        ingestSharedEnemyWaves(myNow);
    }

    private boolean historyReady() {
        return myHistory.size() >= 3 && enemyHistory.size() >= 3;
    }

    private EnemyWave buildEnemyWave(Wave sharedWave, long currentTime) {
        Snapshot my2 = nth(myHistory, 2);
        Snapshot enemy1 = nth(enemyHistory, 1);
        Snapshot enemy2 = nth(enemyHistory, 2);
        if (my2 == null || enemy1 == null || enemy2 == null) {
            throw new IllegalStateException("Shield history unavailable while creating an enemy wave");
        }
        if (sharedWave == null || !sharedWave.isEnemy || sharedWave.isVirtual) {
            throw new IllegalArgumentException("Shield mode requires a real shared enemy wave");
        }
        long expectedFireTime = currentTime - 1L;
        if (sharedWave.fireTime != expectedFireTime) {
            throw new IllegalStateException(
                    "Shared enemy wave fireTime mismatch: expected " + expectedFireTime
                            + " but was " + sharedWave.fireTime);
        }
        double originError = Point2D.distance(sharedWave.originX, sharedWave.originY, enemy1.x, enemy1.y);
        if (originError > SHARED_WAVE_ORIGIN_TOLERANCE) {
            throw new IllegalStateException(
                    "Shared enemy wave origin mismatch: error=" + originError
                            + ", shared=(" + sharedWave.originX + "," + sharedWave.originY + ")"
                            + ", scan=(" + enemy1.x + "," + enemy1.y + ")");
        }
        double firepower = sharedWave.getFirepower();

        double[] predictedHeadings = new double[OPTIONS];
        predictedHeadings[0] = absoluteBearing(enemy2.x, enemy2.y, my2.x, my2.y);
        predictedHeadings[1] = normalize(predictedHeadings[0] + enemy1.heading - enemy2.heading);
        predictedHeadings[2] = absoluteBearing(enemy1.x, enemy1.y, my2.x, my2.y);
        Point2D.Double predictedNext = project(enemy2.x, enemy2.y, enemy2.heading, enemy2.velocity);
        predictedHeadings[3] = absoluteBearing(predictedNext.x, predictedNext.y, my2.x, my2.y);

        for (int i = 0; i < BASE_OPTIONS; i++) {
            predictedHeadings[i + BASE_OPTIONS] = normalize(predictedHeadings[i] + bootstrapState.offsets[i]);
            predictedHeadings[i + BASE_OPTIONS * 2] =
                    normalize(predictedHeadings[i] + lateralDirection * bootstrapState.directionalOffsets[i]);
        }

        EnemyWave wave = new EnemyWave();
        wave.id = nextWaveId++;
        wave.sharedWave = sharedWave;
        wave.fireTime = sharedWave.fireTime;
        wave.originX = sharedWave.originX;
        wave.originY = sharedWave.originY;
        wave.power = firepower;
        wave.speed = sharedWave.speed;
        wave.predictedHeadings = predictedHeadings;
        wave.bestOptionIndex = selectBestOptionIndex(bootstrapState);
        wave.direction = lateralDirection;
        return wave;
    }

    private void ingestSharedEnemyWaves(Snapshot myNow) {
        List<Wave> sharedEnemyWaves = info.getEnemyWaves();
        if (sharedEnemyWaves == null) {
            throw new IllegalStateException("Shield mode requires shared enemy waves from Info");
        }
        for (int i = 0; i < sharedEnemyWaves.size(); i++) {
            Wave sharedWave = sharedEnemyWaves.get(i);
            if (!sharedWave.isEnemy || sharedWave.isVirtual) {
                continue;
            }
            if (enemyWavesBySharedWave.containsKey(sharedWave)) {
                continue;
            }
            if (sharedWave.fireTime != myNow.time - 1L) {
                continue;
            }
            if (!historyReady()) {
                continue;
            }
            EnemyWave newWave = buildEnemyWave(sharedWave, myNow.time);
            enemyWaves.add(newWave);
            enemyWavesBySharedWave.put(sharedWave, newWave);
            enemyShots++;
            lastDetectedEnemyShotTime = newWave.fireTime;
            lastDetectedEnemyBulletPower = newWave.power;
            double remainingEnemyGunHeat = Math.max(0.0, Rules.getGunHeat(newWave.power) - myNow.gunCoolingRate);
            estimatedEnemyNextFireTime = myNow.time
                    + Math.max(1, ticksUntilGunReady(remainingEnemyGunHeat, myNow.gunCoolingRate));
        }
    }

    private void updateEnemyWaves(Snapshot myNow) {
        List<Wave> sharedEnemyWaves = info.getEnemyWaves();
        if (sharedEnemyWaves == null) {
            throw new IllegalStateException("Shield mode requires shared enemy waves from Info");
        }
        Iterator<EnemyWave> iterator = enemyWaves.iterator();
        while (iterator.hasNext()) {
            EnemyWave wave = iterator.next();
            if (wave.sharedWave == null) {
                throw new IllegalStateException("Shield wave lost shared-wave binding");
            }
            if (!sharedEnemyWaves.contains(wave.sharedWave)) {
                iterator.remove();
                enemyWavesBySharedWave.remove(wave.sharedWave);
                continue;
            }
            if (wave.resolved) {
                iterator.remove();
                enemyWavesBySharedWave.remove(wave.sharedWave);
                continue;
            }
            if (waveHasPassed(wave, myNow.x, myNow.y, myNow.time)) {
                iterator.remove();
                enemyWavesBySharedWave.remove(wave.sharedWave);
            }
        }
    }

    private EnemyWave selectWaveToShield(Snapshot myNow) {
        EnemyWave best = null;
        for (int i = 0; i < enemyWaves.size(); i++) {
            EnemyWave wave = enemyWaves.get(i);
            if (wave.resolved || wave.shieldAttempted) {
                continue;
            }
            if (!isLikelyThreat(wave, myNow)) {
                continue;
            }
            if (best == null || wave.fireTime < best.fireTime) {
                best = wave;
            }
        }
        return best;
    }

    private ShieldPlan buildShieldPlan(EnemyWave wave, Snapshot myNow) {
        long fireTime = myNow.time + Math.max(1, ticksUntilShieldGunReady(myNow.gunHeat, myNow.gunCoolingRate));
        ShieldPlan best = buildShieldPlanForFireTime(wave, myNow, fireTime);
        return best;
    }

    private ShieldPlan buildShieldPlanForFireTime(EnemyWave wave, Snapshot myNow, long fireTime) {
        double bulletHeading = wave.predictedHeadings[wave.bestOptionIndex];
        double bodyHeading = parallelHeading(
                absoluteBearing(myNow.x, myNow.y, wave.originX, wave.originY),
                myNow.heading);

        int wiggleSign = (enemyShots & 1) == 0 ? 1 : -1;
        double chosenWiggle = wiggleSign * WIGGLE_SIZE;
        List<ShieldPlan> plans = new ArrayList<ShieldPlan>();
        for (double wiggleDistance : new double[] { chosenWiggle, 0.0 }) {
            Point2D.Double fireLocation = Math.abs(wiggleDistance) > 1e-9
                    ? project(myNow.x, myNow.y, bodyHeading, wiggleDistance)
                    : new Point2D.Double(myNow.x, myNow.y);
            List<Double> powers = getCandidatePowers(wave, fireLocation, fireTime, bulletHeading, myNow);
            for (int i = 0; i < powers.size(); i++) {
                ShieldPlan candidate = evaluatePlan(
                        wave,
                        myNow,
                        fireLocation,
                        bodyHeading,
                        wiggleDistance,
                        fireTime,
                        bulletHeading,
                        powers.get(i));
                if (candidate != null) {
                    plans.add(candidate);
                }
            }
        }

        ShieldPlan best = null;
        for (int i = 0; i < plans.size(); i++) {
            ShieldPlan candidate = plans.get(i);
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private List<Double> getCandidatePowers(EnemyWave wave,
                                            Point2D.Double fireLocation,
                                            long fireTime,
                                            double bulletHeading,
                                            Snapshot myNow) {
        List<Double> powers = new ArrayList<Double>();
        if (!enemyHasMoved) {
            powers.add(Double.valueOf(Rules.MIN_BULLET_POWER));
            return powers;
        }

        double maxCandidatePower = Math.min(3.0, myNow.energy);
        if (wave.power > Rules.MIN_BULLET_POWER + 0.01) {
            maxCandidatePower = Math.min(maxCandidatePower, wave.power - 0.01);
        } else {
            maxCandidatePower = Math.min(maxCandidatePower, wave.power);
        }
        if (maxCandidatePower < Rules.MIN_BULLET_POWER) {
            maxCandidatePower = Rules.MIN_BULLET_POWER;
        }

        double scaledEnemyPower = Math.max(0.2, wave.power) * 10.0;
        double factor = Math.pow(scaledEnemyPower, 1.0 / BotConfig.Shield.POWER_SAMPLE_COUNT);
        for (int i = 0; i < BotConfig.Shield.POWER_SAMPLE_COUNT; i++) {
            double power = 0.1 * Math.pow(factor, i);
            if (power <= maxCandidatePower + 1e-9) {
                addPower(powers, power, myNow.energy);
            }
        }

        double lastDistance = Double.POSITIVE_INFINITY;
        long time = fireTime;
        double distance = positionAtMidpoint(wave, bulletHeading, time).distance(fireLocation);
        while (distance < lastDistance) {
            lastDistance = distance;
            time++;
            distance = positionAtMidpoint(wave, bulletHeading, time).distance(fireLocation);
            double neededSpeed = distance / (time - fireTime + 0.5);
            if (neededSpeed > 11.0 && neededSpeed < 19.7) {
                double power = (20.0 - neededSpeed) / 3.0;
                if (power <= maxCandidatePower + 1e-9) {
                    addPower(powers, power, myNow.energy);
                }
            }
        }
        return powers;
    }

    private void addPower(List<Double> powers, double power, double myEnergy) {
        if (power < Rules.MIN_BULLET_POWER || power > Math.min(3.0, myEnergy)) {
            return;
        }
        for (int i = 0; i < powers.size(); i++) {
            if (Math.abs(powers.get(i).doubleValue() - power) < 1e-6) {
                return;
            }
        }
        powers.add(Double.valueOf(power));
    }

    private ShieldPlan evaluatePlan(EnemyWave wave,
                                    Snapshot myNow,
                                    Point2D.Double fireLocation,
                                    double bodyHeading,
                                    double wiggleDistance,
                                    long fireTime,
                                    double bulletHeading,
                                    double firePower) {
        double myBulletSpeed = bulletSpeed(firePower);
        long bodyHitTime = firstBodyContactTime(wave, myNow.x, myNow.y);
        PreciseShadow shadow = computePreciseShadow(
                wave,
                fireLocation,
                fireTime,
                bulletHeading,
                myBulletSpeed,
                bodyHitTime);
        if (shadow == null || !(shadow.width > 1e-8)) {
            return null;
        }

        double fireAngle = shadow.fireAngle;
        boolean alignedByFireTime = isGunTurnReachable(fireAngle, myNow.gunHeading, (int) (fireTime - myNow.time));
        if (!alignedByFireTime) {
            return null;
        }

        double score = 1.0
                + 0.2
                + (shadow.width > 1e-5 ? 0.03 : 0.0)
                + (Math.abs(wiggleDistance) < 1e-9 && shadow.width > 1e-4 ? 0.1 : 0.0)
                + (firePower < wave.power ? 0.01 : 0.0)
                + shadow.width
                - firePower * 1e-10
                - shadow.time * 1e-12;

        ShieldPlan plan = new ShieldPlan();
        plan.wave = wave;
        plan.fireTime = fireTime;
        plan.bodyHeading = bodyHeading;
        plan.fireAngle = fireAngle;
        plan.firePower = firePower;
        plan.wiggleDistance = wiggleDistance;
        plan.width = shadow.width;
        plan.interceptTime = shadow.time;
        plan.collisionX = shadow.collisionX;
        plan.collisionY = shadow.collisionY;
        plan.score = score;
        return plan;
    }

    private BattlePlan executeShieldPlan(ShieldPlan plan, Snapshot myNow) {
        double bodyTurn = Utils.normalRelativeAngle(plan.bodyHeading - myNow.heading);
        double gunTurn = Utils.normalRelativeAngle(plan.fireAngle - myNow.gunHeading);

        if (Math.abs(plan.wiggleDistance) > 1e-9 && myNow.time == plan.fireTime - 1L && !plan.wiggleIssued) {
            plan.wiggleIssued = true;
            return new BattlePlan(plan.wiggleDistance, bodyTurn, gunTurn, 0.0);
        }

        if (myNow.time == plan.fireTime && !plan.fireIssued) {
            double unwind = Math.abs(plan.wiggleDistance) > 1e-9 ? -plan.wiggleDistance : 0.0;
            return new BattlePlan(unwind, bodyTurn, gunTurn, plan.firePower);
        }

        if (myNow.time > plan.fireTime) {
            plan.wave.shieldAttempted = true;
            activePlan = null;
            return holdShieldStance(myNow);
        }

        return new BattlePlan(0.0, bodyTurn, gunTurn, 0.0);
    }

    private boolean isPlanAlive(ShieldPlan plan, Snapshot myNow) {
        if (plan == null || plan.wave == null || plan.wave.resolved) {
            return false;
        }
        if (waveHasPassed(plan.wave, myNow.x, myNow.y, myNow.time)) {
            return false;
        }
        return myNow.time <= plan.fireTime + 1L;
    }

    private BattlePlan holdShieldStance(Snapshot myNow) {
        if (latestEnemy == null) {
            return new BattlePlan(0.0, 0.0, 0.0, 0.0);
        }
        double bodyHeading = parallelHeading(
                absoluteBearing(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y),
                myNow.heading);
        double bodyTurn = Utils.normalRelativeAngle(bodyHeading - myNow.heading);
        double gunTurn = Utils.normalRelativeAngle(latestEnemy.absoluteBearing - myNow.gunHeading);
        return new BattlePlan(0.0, bodyTurn, gunTurn, 0.0);
    }

    private BattlePlan tryOpeningCenterReposition(Snapshot myNow) {
        if (!allowOpeningCenterReposition) {
            return null;
        }
        if (latestEnemy == null || enemyShots > 0) {
            return null;
        }
        EnemyInfo enemy = info != null ? info.getEnemy() : null;
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        if (!(myNow.gunCoolingRate > 0.0)) {
            return null;
        }
        int ticksUntilEnemyGunReady = ticksUntilGunReady(enemy.gunHeat, myNow.gunCoolingRate);
        if (ticksUntilEnemyGunReady <= BotConfig.Shield.OPENING_CENTER_STOP_LEAD_TICKS) {
            return holdShieldStance(myNow);
        }
        double headingToEnemy = absoluteBearing(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y);
        double distanceToEnemy = Point2D.distance(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y);
        double closingVelocity = myNow.velocity * Math.cos(myNow.heading - headingToEnemy);
        if (distanceToEnemy <= BotConfig.Shield.OPENING_CENTER_EMERGENCY_STOP_DISTANCE && closingVelocity > 1e-6) {
            return holdShieldStance(myNow);
        }

        double desiredHeading = openingAntiGravityHeading(myNow);
        double[] movementInstruction = movementInstructionForHeading(myNow, desiredHeading);
        double gunTurn = Utils.normalRelativeAngle(latestEnemy.absoluteBearing - myNow.gunHeading);
        return new BattlePlan(movementInstruction[0], movementInstruction[1], gunTurn, 0.0);
    }

    private double openingAntiGravityHeading(Snapshot myNow) {
        if (myNow == null || latestEnemy == null || info == null) {
            return 0.0;
        }
        double battlefieldWidth = info.getBattlefieldWidth();
        double battlefieldHeight = info.getBattlefieldHeight();
        double margin = PhysicsUtil.WALL_MARGIN;

        double leftDistance = Math.max(1.0, myNow.x - margin);
        double rightDistance = Math.max(1.0, battlefieldWidth - margin - myNow.x);
        double bottomDistance = Math.max(1.0, myNow.y - margin);
        double topDistance = Math.max(1.0, battlefieldHeight - margin - myNow.y);

        double forceX = 1.0 / (leftDistance * leftDistance) - 1.0 / (rightDistance * rightDistance);
        double forceY = 1.0 / (bottomDistance * bottomDistance) - 1.0 / (topDistance * topDistance);

        double enemyDx = myNow.x - latestEnemy.x;
        double enemyDy = myNow.y - latestEnemy.y;
        double enemyDistanceSquared = enemyDx * enemyDx + enemyDy * enemyDy;
        if (enemyDistanceSquared > 1e-9) {
            double enemyDistance = Math.sqrt(enemyDistanceSquared);
            double enemyForceScale = 2.0 / (enemyDistanceSquared * enemyDistance);
            forceX += enemyDx * enemyForceScale;
            forceY += enemyDy * enemyForceScale;
        }

        if (Math.abs(forceX) <= 1e-12 && Math.abs(forceY) <= 1e-12) {
            return normalize(latestEnemy.absoluteBearing + Math.PI);
        }
        return absoluteBearing(0.0, 0.0, forceX, forceY);
    }

    private double[] movementInstructionForHeading(Snapshot myNow, double desiredHeading) {
        double turnAngle = Utils.normalRelativeAngle(desiredHeading - myNow.heading);
        double moveDistance = OPENING_ANTI_GRAVITY_COMMAND_DISTANCE;
        if (Math.abs(turnAngle) > 0.5 * Math.PI) {
            turnAngle = Utils.normalRelativeAngle(turnAngle + Math.PI);
            moveDistance = -OPENING_ANTI_GRAVITY_COMMAND_DISTANCE;
        }
        return new double[] { moveDistance, turnAngle };
    }

    private BattlePlan tryFireFinisher(Snapshot myNow) {
        if (latestEnemy == null || latestEnemy.energy >= Rules.MIN_BULLET_POWER) {
            return null;
        }
        if (hasUnresolvedEnemyWaves()) {
            return null;
        }
        if (myNow.energy <= Rules.MIN_BULLET_POWER) {
            return null;
        }

        double bodyHeading = parallelHeading(
                absoluteBearing(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y),
                myNow.heading);
        double bodyTurn = Utils.normalRelativeAngle(bodyHeading - myNow.heading);
        double fireAngle = aggressiveFireAngle(Rules.MIN_BULLET_POWER);
        if (!Double.isFinite(fireAngle)) {
            return null;
        }
        double gunTurn = Utils.normalRelativeAngle(fireAngle - myNow.gunHeading);
        double firePower = 0.0;
        if (isGunReady(myNow.gunHeat)) {
            firePower = Rules.MIN_BULLET_POWER;
        }
        return new BattlePlan(0.0, bodyTurn, gunTurn, firePower);
    }

    private boolean hasUnresolvedEnemyWaves() {
        for (int i = 0; i < enemyWaves.size(); i++) {
            if (!enemyWaves.get(i).resolved) {
                return true;
            }
        }
        return false;
    }

    private BattlePlan tryFireDirectAttack(Snapshot myNow) {
        if (latestEnemy == null || latestEnemy.energy < Rules.MIN_BULLET_POWER) {
            return null;
        }
        EnemyInfo enemy = info != null ? info.getEnemy() : null;
        AggressiveAttackDecision attackDecision = selectDirectAttackDecision(myNow);
        if (attackDecision == null || !(attackDecision.power >= Rules.MIN_BULLET_POWER)) {
            return null;
        }
        double attackPower = attackDecision.power;
        double fireAngle = aggressiveFireAngle(attackPower);
        if (!Double.isFinite(fireAngle)) {
            return null;
        }
        double gunTurn = Utils.normalRelativeAngle(fireAngle - myNow.gunHeading);
        double firePower = 0.0;
        if (isGunReady(myNow.gunHeat) && isAggressiveFireAllowedNow(myNow, enemy)) {
            firePower = attackPower;
            pendingAggressiveShotTime = myNow.time;
        }

        double bodyHeading = parallelHeading(
                absoluteBearing(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y),
                myNow.heading);
        return new BattlePlan(
                0.0,
                Utils.normalRelativeAngle(bodyHeading - myNow.heading),
                gunTurn,
                firePower);
    }

    private double aggressiveFireAngle(double firePower) {
        if (gun == null) {
            throw new IllegalStateException("Shield mode requires initialized gun service");
        }
        return gun.getOptimalUnconstrainedFiringAngle(firePower);
    }

    private AggressiveAttackDecision selectDirectAttackDecision(Snapshot myNow) {
        EnemyInfo enemy = info != null ? info.getEnemy() : null;
        if (!isAggressiveAttackOpportunityAllowed(myNow, enemy)) {
            return null;
        }
        boolean fireAllowedNow = isAggressiveFireAllowedNow(myNow, enemy);
        double aggressivePower = Math.min(3.0, myNow.energy);
        aggressivePower = Math.min(aggressivePower, latestEnemy.energy);
        aggressivePower = Math.min(aggressivePower, PhysicsUtil.requiredBulletPowerForDamage(latestEnemy.energy));
        if (aggressivePower < Rules.MIN_BULLET_POWER) {
            return null;
        }
        double distance = Point2D.distance(myNow.x, myNow.y, latestEnemy.x, latestEnemy.y);
        if (fireAllowedNow && distance <= BotConfig.Shield.AGGRESSIVE_CLOSE_RANGE_RADIUS) {
            return new AggressiveAttackDecision(aggressivePower, "close-range");
        }
        aggressivePower = Math.min(aggressivePower, Math.max(Rules.MIN_BULLET_POWER, Math.min(3,
                distance < 100 ? 3 : Math.min(latestEnemy.energy / 4,
                        Math.min(myNow.energy / 10, 1 + 400 / distance)))));
        double maxPower = Math.min(aggressivePower, myNow.energy - Rules.MIN_BULLET_POWER);
        double maxPowerForEnergyLead = myNow.energy - latestEnemy.energy;
        maxPower = Math.min(maxPower, maxPowerForEnergyLead);
        if (maxPower < Rules.MIN_BULLET_POWER) {
            return null;
        }
        if (shouldUseIdleAggressiveShot(myNow)) {
            return new AggressiveAttackDecision(Math.floor(maxPower * 10.0) / 10.0, "idle");
        }
        if (!fireAllowedNow) {
            return null;
        }
        if (!isShieldDeadlineAggressionAllowed(myNow)) {
            return null;
        }
        if (!BotConfig.Shield.ENABLE_SHIELD_SLACK_AGGRESSION) {
            return null;
        }

        double candidate = Math.floor(maxPower * 10.0) / 10.0;
        while (candidate >= Rules.MIN_BULLET_POWER - 1e-9) {
            if (canShieldNextExpectedShotAfterAggressiveFire(myNow, enemy, candidate)) {
                return new AggressiveAttackDecision(candidate, "slack");
            }
            candidate -= 0.1;
        }
        return null;
    }

    private boolean shouldUseIdleAggressiveShot(Snapshot myNow) {
        if (myNow.time < BotConfig.Shield.EARLY_ROUND_IDLE_AGGRESSION_GUARD_TICKS) {
            return false;
        }
        if (!(myNow.gunCoolingRate > 0.0)) {
            return false;
        }
        long ticksSinceLastShot = lastDetectedEnemyShotTime == Long.MIN_VALUE
                ? myNow.time
                : myNow.time - lastDetectedEnemyShotTime;
        long idleThresholdTicks = (long) Math.ceil(
                BotConfig.Shield.IDLE_AGGRESSION_GUN_HEAT_MULTIPLIER
                        * Rules.getGunHeat(Rules.MAX_BULLET_POWER)
                        / myNow.gunCoolingRate);
        return ticksSinceLastShot >= Math.max(1L, idleThresholdTicks);
    }

    private boolean isShieldDeadlineAggressionAllowed(Snapshot myNow) {
        return myNow.time >= BotConfig.Shield.EARLY_ROUND_DEADLINE_AGGRESSION_GUARD_TICKS;
    }

    private boolean isAggressiveAttackOpportunityAllowed(Snapshot myNow, EnemyInfo enemy) {
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return false;
        }
        if (!(myNow.gunCoolingRate > 0.0)) {
            return false;
        }
        if (hasUnshieldedEnemyWavesInFlight()) {
            return false;
        }
        return true;
    }

    private boolean isAggressiveFireAllowedNow(Snapshot myNow, EnemyInfo enemy) {
        return isAggressiveAttackOpportunityAllowed(myNow, enemy)
                && myNow.gunHeat <= enemy.gunHeat + 1e-9;
    }

    private boolean hasUnshieldedEnemyWavesInFlight() {
        for (int i = 0; i < enemyWaves.size(); i++) {
            EnemyWave wave = enemyWaves.get(i);
            if (!wave.resolved && !wave.shieldAttempted) {
                return true;
            }
        }
        return false;
    }

    private boolean canShieldNextExpectedShotAfterAggressiveFire(Snapshot myNow,
                                                                 EnemyInfo enemy,
                                                                 double firePower) {
        long expectedEnemyFireTime = expectedNextEnemyFireTime(myNow, enemy);
        if (expectedEnemyFireTime == Long.MIN_VALUE) {
            return false;
        }
        EnemyWave expectedWave = buildPessimisticExpectedNextWave(myNow, expectedEnemyFireTime);
        if (expectedWave == null) {
            return false;
        }
        long conservativeShieldReadyTime = conservativeShieldReadyTimeAfterAggressiveFire(myNow, firePower);
        return latestShieldFireTime(expectedWave, myNow, conservativeShieldReadyTime) != Long.MIN_VALUE;
    }

    private long latestShieldFireTime(EnemyWave wave, Snapshot myNow) {
        return latestShieldFireTime(wave, myNow, myNow.time + 1L);
    }

    private long latestShieldFireTime(EnemyWave wave, Snapshot myNow, long earliest) {
        long latest = firstBodyContactTime(wave, myNow.x, myNow.y);
        for (long fireTime = latest; fireTime >= earliest; fireTime--) {
            if (buildShieldPlanForFireTime(wave, myNow, fireTime) != null) {
                return fireTime;
            }
        }
        return Long.MIN_VALUE;
    }

    private EnemyWave buildImmediateThreatWave(Snapshot myNow) {
        return buildSyntheticHeadOnWave(latestEnemy.x, latestEnemy.y, myNow.time, myNow);
    }

    private long expectedNextEnemyFireTime(Snapshot myNow, EnemyInfo enemy) {
        if (enemy == null || !(myNow.gunCoolingRate > 0.0)) {
            return Long.MIN_VALUE;
        }
        long enemyReadyTicks = Math.max(0L, ticksUntilGunReady(enemy.gunHeat, myNow.gunCoolingRate));
        long expectedFireTime = myNow.time + enemyReadyTicks;
        if (estimatedEnemyNextFireTime > myNow.time) {
            expectedFireTime = Math.min(expectedFireTime, estimatedEnemyNextFireTime);
        }
        return expectedFireTime;
    }

    private long conservativeShieldReadyTimeAfterAggressiveFire(Snapshot myNow, double firePower) {
        double conservativeGunHeat = myNow.gunHeat + Rules.getGunHeat(firePower);
        long readyTicks = Math.max(1L, ticksUntilShieldGunReady(conservativeGunHeat, myNow.gunCoolingRate));
        return myNow.time + readyTicks + BotConfig.Shield.AGGRESSIVE_SHOT_SHIELD_SAFETY_TICKS;
    }

    private EnemyWave buildPessimisticExpectedNextWave(Snapshot myNow, long expectedEnemyFireTime) {
        if (latestEnemy == null) {
            return null;
        }
        Point2D.Double expectedSource = projectPessimisticEnemySource(myNow, expectedEnemyFireTime);
        return buildSyntheticHeadOnWave(expectedSource.x, expectedSource.y, expectedEnemyFireTime, myNow);
    }

    private Point2D.Double projectPessimisticEnemySource(Snapshot myNow, long expectedEnemyFireTime) {
        double headingToMe = absoluteBearing(latestEnemy.x, latestEnemy.y, myNow.x, myNow.y);
        double currentDistance = Point2D.distance(latestEnemy.x, latestEnemy.y, myNow.x, myNow.y);
        double advancingVelocity = latestEnemy.velocity * Math.cos(latestEnemy.heading - headingToMe);
        long ticks = Math.max(0L, expectedEnemyFireTime - myNow.time);
        double projectedDistance = currentDistance;

        for (long i = 0; i < ticks; i++) {
            projectedDistance = Math.max(0.0, projectedDistance - advancingVelocity);
            advancingVelocity = Math.min(Rules.MAX_VELOCITY, advancingVelocity + Rules.ACCELERATION);
        }

        double straightLineClosingDistance = Math.max(0.0, currentDistance - Rules.MAX_VELOCITY * ticks);
        double pessimisticDistance = Math.min(projectedDistance, straightLineClosingDistance);
        return project(myNow.x, myNow.y, normalize(headingToMe + Math.PI), pessimisticDistance);
    }

    private EnemyWave buildSyntheticHeadOnWave(double originX, double originY, long fireTime, Snapshot myNow) {
        EnemyWave wave = new EnemyWave();
        wave.id = -1L;
        wave.fireTime = fireTime;
        wave.originX = originX;
        wave.originY = originY;
        wave.power = Math.max(Rules.MIN_BULLET_POWER, Math.min(lastDetectedEnemyBulletPower, latestEnemy.energy));
        wave.speed = bulletSpeed(wave.power);
        wave.predictedHeadings = new double[OPTIONS];
        double heading = absoluteBearing(originX, originY, myNow.x, myNow.y);
        for (int i = 0; i < OPTIONS; i++) {
            wave.predictedHeadings[i] = heading;
        }
        wave.bestOptionIndex = 0;
        wave.direction = lateralDirection;
        return wave;
    }

    private boolean isLikelyThreat(EnemyWave wave, Snapshot myNow) {
        double predictedHeading = wave.predictedHeadings[wave.bestOptionIndex];
        double[] interval = bodyAngularInterval(wave.originX, wave.originY, myNow.x, myNow.y);
        if (interval == null) {
            return false;
        }
        double midpoint = 0.5 * (interval[0] + interval[1]);
        double unwrapped = predictedHeading
                + Math.rint((midpoint - predictedHeading) / (Math.PI * 2.0)) * (Math.PI * 2.0);
        return unwrapped >= interval[0] - 0.02 && unwrapped <= interval[1] + 0.02;
    }

    private void observeEnemyBullet(Bullet bullet, long time) {
        EnemyWave wave = matchEnemyWave(bullet, time);
        if (wave == null) {
            return;
        }

        // The persisted bootstrap is only an opening prior. The first real shield observation
        // replaces it completely so stale opponent behavior never contaminates live battle data.
        if (bootstrapState.persistedBootstrapLoaded && !bootstrapState.currentBattleShieldDataAvailable) {
            clearShieldStatistics(bootstrapState);
            bootstrapState.persistedBootstrapLoaded = false;
            bootstrapState.persistedBootstrapBestOptionIndex = 0;
            bootstrapState.persistedBootstrapAngleOffset = 0.0;
        }
        bootstrapState.currentBattleShieldDataAvailable = true;

        for (int i = 0; i < OPTIONS; i++) {
            double diff = Utils.normalRelativeAngle(bullet.getHeadingRadians() - wave.predictedHeadings[i]);
            bootstrapState.optionSquaredErrorSums[i] += diff * diff;
            bootstrapState.optionObservationCounts[i]++;
            if (Math.abs(diff) < MODEL_SCORE_MATCH_EPSILON) {
                bootstrapState.optionScores[i]++;
            }
            if (i < BASE_OPTIONS) {
                bootstrapState.offsets[i] =
                        (bootstrapState.offsets[i] * bootstrapState.offsetCounts[i] + diff)
                                / (bootstrapState.offsetCounts[i] + 1);
                bootstrapState.directionalOffsets[i] =
                        (bootstrapState.directionalOffsets[i] * bootstrapState.offsetCounts[i]
                                + wave.direction * diff)
                                / (bootstrapState.offsetCounts[i] + 1);
                bootstrapState.offsetCounts[i]++;
            }
        }

        wave.resolved = true;
        if (activePlan != null && activePlan.wave == wave) {
            activePlan = null;
        }
    }

    private EnemyWave matchEnemyWave(Bullet bullet, long time) {
        if (bullet == null) {
            return null;
        }
        EnemyWave best = null;
        double bestError = Double.POSITIVE_INFINITY;
        for (int i = 0; i < enemyWaves.size(); i++) {
            EnemyWave wave = enemyWaves.get(i);
            if (wave.resolved) {
                continue;
            }
            if (Math.abs(wave.power - bullet.getPower()) > WAVE_MATCH_POWER_TOLERANCE) {
                continue;
            }
            double distance = Point2D.distance(wave.originX, wave.originY, bullet.getX(), bullet.getY());
            double error = Math.abs(effectiveEnemyDistance(wave, time) - distance);
            if (error < bestError) {
                bestError = error;
                best = wave;
            }
        }
        if (bestError <= 1.1 * bullet.getVelocity()) {
            return best;
        }
        return null;
    }

    @Override
    public void onHitByBullet(HitByBulletEvent event) {
        observeEnemyBullet(event.getBullet(), event.getTime());
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent event) {
        observeEnemyBullet(event.getHitBullet(), event.getTime());
    }

    @Override
    public void onBulletHit(BulletHitEvent event) {
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent event) {
        lastScanEvent = event;
    }

    @Override
    public void onRobotDeath(RobotDeathEvent event) {
        activePlan = null;
        firedShieldCollisionPaths.clear();
        firedAggressiveShotPaths.clear();
        pendingAggressiveShotTime = Long.MIN_VALUE;
    }

    public static void startBattlePersistence(ModeId modeId) {
        ShieldBootstrapState state = stateFor(modeId);
        clearShieldStatistics(state);
        state.currentBattleShieldDataAvailable = false;
        state.persistedBootstrapLoaded = false;
        state.persistedBootstrapBestOptionIndex = 0;
        state.persistedBootstrapAngleOffset = 0.0;
    }

    public static void loadPersistedBootstrap(ModeId modeId, int sectionVersion, byte[] payload) {
        if (payload == null) {
            return;
        }
        ShieldBootstrapState state = stateFor(modeId);
        if (sectionVersion != PERSISTED_BOOTSTRAP_SECTION_VERSION) {
            throw new IllegalStateException("Unsupported shield-bootstrap section version " + sectionVersion);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (payload.length != PERSISTED_BOOTSTRAP_BYTES) {
                throw new IllegalStateException("Unexpected shield-bootstrap payload length");
            }
            int bestOptionIndex = in.readUnsignedByte();
            if (bestOptionIndex < 0 || bestOptionIndex >= OPTIONS) {
                throw new IllegalStateException("Invalid shield-bootstrap option index " + bestOptionIndex);
            }
            double angleOffset = in.readDouble();
            if (!Double.isFinite(angleOffset)) {
                throw new IllegalStateException("Shield-bootstrap angle offset must be finite");
            }
            if (in.available() != 0) {
                throw new IllegalStateException("Shield-bootstrap payload contained trailing bytes");
            }
            state.persistedBootstrapLoaded = true;
            state.persistedBootstrapBestOptionIndex = bestOptionIndex;
            state.persistedBootstrapAngleOffset = angleOffset;
            applyPersistedBootstrap(state, bestOptionIndex, angleOffset);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable shield-bootstrap payload", e);
        }
    }

    public static boolean hasPersistedBootstrapData(ModeId modeId) {
        ShieldBootstrapState state = stateFor(modeId);
        return state.currentBattleShieldDataAvailable || state.persistedBootstrapLoaded;
    }

    public static boolean isAnyPersistedBootstrapLoaded() {
        return stateFor(ModeId.BULLET_SHIELD).persistedBootstrapLoaded
                || stateFor(ModeId.MOVING_BULLET_SHIELD).persistedBootstrapLoaded;
    }

    public static int getPersistedBootstrapBestOptionIndex(ModeId modeId) {
        return stateFor(modeId).persistedBootstrapBestOptionIndex;
    }

    public static double getPersistedBootstrapAngleOffset(ModeId modeId) {
        return stateFor(modeId).persistedBootstrapAngleOffset;
    }

    public static byte[] createPersistedBootstrapPayload(ModeId modeId,
                                                         int maxPayloadBytes,
                                                         boolean includeCurrentBattleData) {
        ShieldBootstrapState state = stateFor(modeId);
        int bestOptionIndex;
        double angleOffset;
        if (includeCurrentBattleData && state.currentBattleShieldDataAvailable) {
            bestOptionIndex = selectBestOptionIndex(state);
            angleOffset = angleOffsetForOption(state, bestOptionIndex);
        } else if (state.persistedBootstrapLoaded) {
            bestOptionIndex = state.persistedBootstrapBestOptionIndex;
            angleOffset = state.persistedBootstrapAngleOffset;
        } else {
            return null;
        }
        if (maxPayloadBytes < PERSISTED_BOOTSTRAP_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save shield bootstrap: payload budget=" + maxPayloadBytes + " bytes");
        }
        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(bestOptionIndex);
            out.writeDouble(angleOffset);
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length > maxPayloadBytes) {
                throw new IllegalStateException(
                        "Insufficient data quota to save shield bootstrap: required=" + payload.length
                                + " bytes, available=" + maxPayloadBytes + " bytes");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build shield-bootstrap payload", e);
        }
    }

    private Snapshot captureSelf() {
        if (info == null) {
            return new Snapshot(0.0, 0.0, 0.0, 0.0, 0.0, Double.NaN, 0L, 0.0, 0.0, 0.1);
        }
        RobotSnapshot snapshot = info.captureRobotSnapshot();
        return new Snapshot(
                snapshot.x,
                snapshot.y,
                normalize(snapshot.heading),
                snapshot.velocity,
                snapshot.energy,
                Double.NaN,
                snapshot.time,
                snapshot.gunHeading,
                snapshot.gunHeat,
                snapshot.gunCoolingRate);
    }

    private static void pushFront(Deque<Snapshot> history, Snapshot snapshot) {
        history.addFirst(snapshot);
        while (history.size() > BotConfig.Shield.SNAPSHOT_HISTORY_LIMIT) {
            history.removeLast();
        }
    }

    private static Snapshot nth(Deque<Snapshot> history, int index) {
        if (index < 0 || index >= history.size()) {
            return null;
        }
        int current = 0;
        for (Snapshot snapshot : history) {
            if (current == index) {
                return snapshot;
            }
            current++;
        }
        return null;
    }

    private static double normalize(double angle) {
        return Utils.normalRelativeAngle(angle);
    }

    private static void clearShieldStatistics(ShieldBootstrapState state) {
        for (int i = 0; i < OPTIONS; i++) {
            state.optionScores[i] = 0;
            state.optionObservationCounts[i] = 0;
            state.optionSquaredErrorSums[i] = 0.0;
        }
        for (int i = 0; i < BASE_OPTIONS; i++) {
            state.offsetCounts[i] = 0;
            state.offsets[i] = 0.0;
            state.directionalOffsets[i] = 0.0;
        }
    }

    private static void applyPersistedBootstrap(ShieldBootstrapState state, int bestOptionIndex, double angleOffset) {
        clearShieldStatistics(state);
        state.optionScores[bestOptionIndex] = 1;
        if (bestOptionIndex >= BASE_OPTIONS && bestOptionIndex < BASE_OPTIONS * 2) {
            state.offsets[bestOptionIndex - BASE_OPTIONS] = angleOffset;
        } else if (bestOptionIndex >= BASE_OPTIONS * 2) {
            state.directionalOffsets[bestOptionIndex - BASE_OPTIONS * 2] = angleOffset;
        }
    }

    private static int selectBestOptionIndex(ShieldBootstrapState state) {
        int bestIndex = 0;
        int bestScore = state.optionScores[0];
        double bestAverageSquaredError = averageSquaredError(state, 0);
        for (int i = 1; i < OPTIONS; i++) {
            if (state.optionScores[i] > bestScore) {
                bestIndex = i;
                bestScore = state.optionScores[i];
                bestAverageSquaredError = averageSquaredError(state, i);
                continue;
            }
            if (state.optionScores[i] == bestScore) {
                double candidateAverageSquaredError = averageSquaredError(state, i);
                if (candidateAverageSquaredError < bestAverageSquaredError) {
                    bestIndex = i;
                    bestAverageSquaredError = candidateAverageSquaredError;
                }
            }
        }
        return bestIndex;
    }

    private static double averageSquaredError(ShieldBootstrapState state, int optionIndex) {
        if (state.optionObservationCounts[optionIndex] <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return state.optionSquaredErrorSums[optionIndex] / state.optionObservationCounts[optionIndex];
    }

    private static double angleOffsetForOption(ShieldBootstrapState state, int optionIndex) {
        if (optionIndex >= BASE_OPTIONS && optionIndex < BASE_OPTIONS * 2) {
            return state.offsets[optionIndex - BASE_OPTIONS];
        }
        if (optionIndex >= BASE_OPTIONS * 2) {
            return state.directionalOffsets[optionIndex - BASE_OPTIONS * 2];
        }
        return 0.0;
    }

    private static ShieldBootstrapState[] createBootstrapStates() {
        ShieldBootstrapState[] states = new ShieldBootstrapState[ModeId.values().length];
        for (ModeId modeId : ModeId.values()) {
            states[modeId.ordinal()] = new ShieldBootstrapState();
        }
        return states;
    }

    private static ShieldBootstrapState stateFor(ModeId modeId) {
        validateShieldModeId(modeId);
        return BOOTSTRAP_STATES[modeId.ordinal()];
    }

    private static void validateShieldModeId(ModeId modeId) {
        if (modeId != ModeId.BULLET_SHIELD && modeId != ModeId.MOVING_BULLET_SHIELD) {
            throw new IllegalArgumentException("Shield bootstrap requires a shield mode id: " + modeId);
        }
    }

    private static double bulletSpeed(double power) {
        return 20.0 - 3.0 * power;
    }

    private static boolean isGunReady(double gunHeat) {
        return gunHeat <= BotConfig.GUN_HEAT_READY_EPSILON;
    }

    private static int ticksUntilGunReady(double gunHeat, double gunCoolingRate) {
        if (isGunReady(gunHeat)) {
            return 0;
        }
        return (int) Math.ceil((gunHeat - BotConfig.GUN_HEAT_READY_EPSILON) / gunCoolingRate);
    }

    private static int ticksUntilShieldGunReady(double gunHeat, double gunCoolingRate) {
        if (isGunReady(gunHeat)) {
            return 0;
        }
        double readyTicks = gunHeat / gunCoolingRate;
        int ceilTicks = (int) Math.ceil(readyTicks);
        long nearestInteger = Math.round(readyTicks);
        if (Math.abs(readyTicks - nearestInteger) <= BotConfig.Shield.SHIELD_READY_TICK_EDGE_EPSILON) {
            return Math.max(ceilTicks, (int) nearestInteger + 1);
        }
        return ceilTicks;
    }

    private static boolean isGunTurnReachable(double desiredAngle, double gunHeading, int ticksUntilFire) {
        double budget = Math.max(0, ticksUntilFire) * Rules.GUN_TURN_RATE_RADIANS;
        double needed = Math.abs(Utils.normalRelativeAngle(desiredAngle - gunHeading));
        return needed <= budget + 1e-9;
    }

    private static double parallelHeading(double absBearing, double currentHeading) {
        double cw = normalize(absBearing + 0.5 * Math.PI);
        double ccw = normalize(absBearing - 0.5 * Math.PI);
        double cwTurn = Math.abs(Utils.normalRelativeAngle(cw - currentHeading));
        double ccwTurn = Math.abs(Utils.normalRelativeAngle(ccw - currentHeading));
        return cwTurn <= ccwTurn ? cw : ccw;
    }

    private static double absoluteBearing(double x1, double y1, double x2, double y2) {
        return Math.atan2(x2 - x1, y2 - y1);
    }

    private static Point2D.Double project(double x, double y, double angle, double distance) {
        return new Point2D.Double(
                x + Math.sin(angle) * distance,
                y + Math.cos(angle) * distance);
    }

    private static Point2D.Double positionAtTick(EnemyWave wave, double heading, long time) {
        return project(wave.originX, wave.originY, heading, effectiveEnemyDistance(wave, time));
    }

    private static Point2D.Double positionAtMidpoint(EnemyWave wave, double heading, long time) {
        return project(wave.originX, wave.originY, heading, effectiveEnemyMidpointDistance(wave, time));
    }

    private static PreciseShadow computePreciseShadow(EnemyWave wave,
                                                      Point2D.Double fireLocation,
                                                      long fireTime,
                                                      double bulletHeading,
                                                      double myBulletSpeed,
                                                      long lastUsefulTime) {
        PreciseShadow lastShadow = null;
        long firstSegmentTime = Math.max(fireTime + 1L, wave.fireTime + 1L);
        for (long time = firstSegmentTime; time <= lastUsefulTime; time++) {
            Point2D.Double bulletStart = positionAtTick(wave, bulletHeading, time - 1L);
            Point2D.Double bulletEnd = positionAtTick(wave, bulletHeading, time);

            double startDistance = bulletStart.distance(fireLocation);
            double endDistance = bulletEnd.distance(fireLocation);
            double startRadius = (time - 1L - fireTime) * myBulletSpeed;
            double endRadius = (time - fireTime) * myBulletSpeed;

            if (startDistance < startRadius || endDistance > startDistance) {
                return lastShadow;
            }
            if (endDistance > endRadius) {
                continue;
            }

            Point2D.Double shadowStart = startDistance <= endRadius
                    ? bulletStart
                    : circleSegmentIntersection(fireLocation, bulletStart, bulletEnd, endRadius, false);
            Point2D.Double shadowEnd = endDistance >= startRadius
                    ? bulletEnd
                    : circleSegmentIntersection(fireLocation, bulletStart, bulletEnd, startRadius, true);
            if (shadowStart == null || shadowEnd == null) {
                continue;
            }

            double edge1 = absoluteBearing(fireLocation.x, fireLocation.y, shadowStart.x, shadowStart.y);
            double edge2 = absoluteBearing(fireLocation.x, fireLocation.y, shadowEnd.x, shadowEnd.y);
            double edgeDiff = Utils.normalRelativeAngle(edge1 - edge2);
            double width = Math.abs(edgeDiff);
            if (!(width > 1e-8)) {
                continue;
            }

            PreciseShadow shadow = new PreciseShadow();
            shadow.fireAngle = normalize(edge1 - 0.5 * edgeDiff);
            shadow.width = width;
            shadow.time = time;
            Point2D.Double collisionPoint = raySegmentIntersection(fireLocation, shadow.fireAngle, shadowStart, shadowEnd);
            if (collisionPoint == null) {
                collisionPoint = new Point2D.Double(
                        0.5 * (shadowStart.x + shadowEnd.x),
                        0.5 * (shadowStart.y + shadowEnd.y));
            }
            shadow.collisionX = collisionPoint.x;
            shadow.collisionY = collisionPoint.y;
            lastShadow = shadow;
        }
        return lastShadow;
    }

    private static Point2D.Double raySegmentIntersection(Point2D.Double origin,
                                                         double angle,
                                                         Point2D.Double segmentStart,
                                                         Point2D.Double segmentEnd) {
        double rayX = Math.sin(angle);
        double rayY = Math.cos(angle);
        double segmentX = segmentEnd.x - segmentStart.x;
        double segmentY = segmentEnd.y - segmentStart.y;
        double cross = rayX * segmentY - rayY * segmentX;
        if (Math.abs(cross) < 1e-9) {
            return null;
        }
        double offsetX = segmentStart.x - origin.x;
        double offsetY = segmentStart.y - origin.y;
        double rayDistance = (offsetX * segmentY - offsetY * segmentX) / cross;
        double segmentFraction = (offsetX * rayY - offsetY * rayX) / cross;
        if (rayDistance < -1e-9 || segmentFraction < -1e-9 || segmentFraction > 1.0 + 1e-9) {
            return null;
        }
        return new Point2D.Double(
                origin.x + Math.max(0.0, rayDistance) * rayX,
                origin.y + Math.max(0.0, rayDistance) * rayY);
    }

    private static Point2D.Double circleSegmentIntersection(Point2D.Double origin,
                                                            Point2D.Double p1,
                                                            Point2D.Double p2,
                                                            double radius,
                                                            boolean preferCloserToEnd) {
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double ox = p1.x - origin.x;
        double oy = p1.y - origin.y;
        double a = dx * dx + dy * dy;
        if (a < 1e-12) {
            return null;
        }
        double b = 2.0 * (ox * dx + oy * dy);
        double c = ox * ox + oy * oy - radius * radius;
        double discriminant = b * b - 4.0 * a * c;
        if (discriminant < 0.0) {
            return null;
        }
        double sqrt = Math.sqrt(discriminant);
        double t1 = (-b - sqrt) / (2.0 * a);
        double t2 = (-b + sqrt) / (2.0 * a);
        double chosen = Double.NaN;
        for (double t : new double[] { t1, t2 }) {
            if (t < -1e-9 || t > 1.0 + 1e-9) {
                continue;
            }
            if (!Double.isFinite(chosen)) {
                chosen = t;
                continue;
            }
            if (preferCloserToEnd) {
                if (t > chosen) {
                    chosen = t;
                }
            } else if (t < chosen) {
                chosen = t;
            }
        }
        if (!Double.isFinite(chosen)) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(1.0, chosen));
        return new Point2D.Double(p1.x + clamped * dx, p1.y + clamped * dy);
    }

    private static boolean waveHasPassed(EnemyWave wave, double x, double y, long time) {
        double radius = effectiveEnemyDistance(wave, time);
        return radius > maxDistanceToBody(wave.originX, wave.originY, x, y) + wave.speed;
    }

    private static long firstBodyContactTime(EnemyWave wave, double x, double y) {
        double minDistance = Math.max(0.0, Point2D.distance(wave.originX, wave.originY, x, y) - BODY_HALF_DIAGONAL);
        return wave.fireTime + (long) Math.ceil(minDistance / wave.speed);
    }

    private static double effectiveEnemyDistance(EnemyWave wave, long time) {
        return wave.speed * (time - wave.fireTime);
    }

    private static double effectiveEnemyMidpointDistance(EnemyWave wave, long time) {
        return wave.speed * (time - wave.fireTime - 0.5);
    }

    private static double maxDistanceToBody(double originX, double originY, double x, double y) {
        return Point2D.distance(originX, originY, x, y) + BODY_HALF_DIAGONAL;
    }

    private static double[] bodyAngularInterval(double originX, double originY, double x, double y) {
        double center = absoluteBearing(originX, originY, x, y);
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double[] xs = new double[] { x - BODY_HALF_WIDTH, x - BODY_HALF_WIDTH, x + BODY_HALF_WIDTH, x + BODY_HALF_WIDTH };
        double[] ys = new double[] { y - BODY_HALF_WIDTH, y + BODY_HALF_WIDTH, y - BODY_HALF_WIDTH, y + BODY_HALF_WIDTH };
        for (int i = 0; i < xs.length; i++) {
            double angle = absoluteBearing(originX, originY, xs[i], ys[i]);
            angle += Math.rint((center - angle) / (Math.PI * 2.0)) * (Math.PI * 2.0);
            min = Math.min(min, angle);
            max = Math.max(max, angle);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return null;
        }
        return new double[] { min, max };
    }

    private static final class ShieldBootstrapState {
        final int[] optionScores = new int[OPTIONS];
        final int[] optionObservationCounts = new int[OPTIONS];
        final double[] optionSquaredErrorSums = new double[OPTIONS];
        final int[] offsetCounts = new int[BASE_OPTIONS];
        final double[] offsets = new double[BASE_OPTIONS];
        final double[] directionalOffsets = new double[BASE_OPTIONS];
        boolean currentBattleShieldDataAvailable;
        boolean persistedBootstrapLoaded;
        int persistedBootstrapBestOptionIndex;
        double persistedBootstrapAngleOffset;
    }

    private static final class Snapshot {
        final double x;
        final double y;
        final double heading;
        final double velocity;
        final double energy;
        final double absoluteBearing;
        final long time;
        final double gunHeading;
        final double gunHeat;
        final double gunCoolingRate;

        Snapshot(double x,
                 double y,
                 double heading,
                 double velocity,
                 double energy,
                 double absoluteBearing,
                 long time,
                 double gunHeading,
                 double gunHeat,
                 double gunCoolingRate) {
            this.x = x;
            this.y = y;
            this.heading = heading;
            this.velocity = velocity;
            this.energy = energy;
            this.absoluteBearing = absoluteBearing;
            this.time = time;
            this.gunHeading = gunHeading;
            this.gunHeat = gunHeat;
            this.gunCoolingRate = gunCoolingRate;
        }
    }

    private static final class EnemyWave {
        long id;
        Wave sharedWave;
        long fireTime;
        double originX;
        double originY;
        double power;
        double speed;
        double[] predictedHeadings;
        int bestOptionIndex;
        double direction;
        boolean shieldAttempted;
        boolean resolved;
    }

    private static final class AggressiveAttackDecision {
        final double power;
        final String reason;

        AggressiveAttackDecision(double power, String reason) {
            this.power = power;
            this.reason = reason;
        }
    }

    private static final class ShieldPlan {
        EnemyWave wave;
        long fireTime;
        long interceptTime;
        double bodyHeading;
        double fireAngle;
        double firePower;
        double wiggleDistance;
        double width;
        double collisionX;
        double collisionY;
        double score;
        boolean wiggleIssued;
        boolean fireIssued;
    }

    private static final class BulletLinePath {
        final long startTime;
        final long interceptTime;
        final double startX;
        final double startY;
        final double endX;
        final double endY;
        final double speed;
        final double unitX;
        final double unitY;
        final double pathLength;

        BulletLinePath(long startTime,
                       long interceptTime,
                       double startX,
                       double startY,
                       double endX,
                       double endY,
                       double speed,
                       double unitX,
                       double unitY,
                       double pathLength) {
            this.startTime = startTime;
            this.interceptTime = interceptTime;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.speed = speed;
            this.unitX = unitX;
            this.unitY = unitY;
            this.pathLength = pathLength;
        }
    }

    private static final class ShieldCollisionPath {
        final BulletLinePath myPath;
        final BulletLinePath enemyPath;
        final long interceptTime;

        ShieldCollisionPath(BulletLinePath myPath, BulletLinePath enemyPath, long interceptTime) {
            this.myPath = myPath;
            this.enemyPath = enemyPath;
            this.interceptTime = interceptTime;
        }
    }

    private static final class AggressiveShotPath {
        final BulletLinePath bulletPath;

        AggressiveShotPath(BulletLinePath bulletPath) {
            this.bulletPath = bulletPath;
        }
    }

    private static final class PreciseShadow {
        long time;
        double fireAngle;
        double width;
        double collisionX;
        double collisionY;
    }
}
