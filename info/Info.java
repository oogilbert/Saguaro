package oog.mega.saguaro.info;

import java.awt.Graphics2D;
import java.util.List;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.EnemyTracker;
import oog.mega.saguaro.info.state.BulletPowerHitRateTracker;
import oog.mega.saguaro.info.state.EnemyBulletHitRateTracker;
import oog.mega.saguaro.info.state.PrecisePredictionTracker;
import oog.mega.saguaro.info.state.RobotMotionTracker;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.state.ScoreTracker;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveManager;
import oog.mega.saguaro.render.PathRenderer;
import oog.mega.saguaro.render.PathOverlay;
import oog.mega.saguaro.render.RenderState;
import oog.mega.saguaro.render.WaveRenderer;
import oog.mega.saguaro.mode.perfectprediction.ReactiveOpponentPredictor;
import robocode.Bullet;
import robocode.BulletHitBulletEvent;
import robocode.BulletHitEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;

public class Info {
    private Saguaro robot;
    private RoundOutcomeProfile roundOutcomeProfile;
    private ObservationProfile observationProfile;
    private BattleDataStore dataStore;
    private final EnemyTracker enemyTracker = new EnemyTracker();
    private final WaveManager waveManager = new WaveManager();
    private final WaveRenderer waveRenderer = new WaveRenderer();
    private final PathRenderer pathRenderer = new PathRenderer();
    private final ScoreTracker scoreTracker = new ScoreTracker();
    private final BulletPowerHitRateTracker bulletPowerHitRateTracker = BulletPowerHitRateTracker.INSTANCE;
    private final EnemyBulletHitRateTracker enemyBulletHitRateTracker = new EnemyBulletHitRateTracker();
    private final RobotMotionTracker robotMotionTracker = new RobotMotionTracker();
    private final PrecisePredictionTracker precisePredictionTracker = new PrecisePredictionTracker();
    private double battlefieldWidth;
    private double battlefieldHeight;
    private long enemyWaveDangerRevision;
    private double trackedOurEnergy;
    private int ourShotsFiredThisBattle;
    private int enemyShotsFiredThisBattle;

    public void init(Saguaro robot,
                     RoundOutcomeProfile roundOutcomeProfile,
                     ObservationProfile observationProfile,
                     BattleDataStore dataStore) {
        this.robot = robot;
        this.dataStore = requireDataStore(dataStore);
        setRoundOutcomeProfile(roundOutcomeProfile);
        setObservationProfile(observationProfile);
        this.battlefieldWidth = robot.getBattleFieldWidth();
        this.battlefieldHeight = robot.getBattleFieldHeight();
        if (robot.getRoundNum() == 0) {
            enemyBulletHitRateTracker.startBattle();
            ourShotsFiredThisBattle = 0;
            enemyShotsFiredThisBattle = 0;
        }
        enemyTracker.init(robot, roundOutcomeProfile, this.dataStore);
        scoreTracker.init(roundOutcomeProfile);
        bulletPowerHitRateTracker.startRound();
        enemyBulletHitRateTracker.startRound();
        robotMotionTracker.init(robot);
        precisePredictionTracker.init(robot, battlefieldWidth, battlefieldHeight);
        waveManager.init(robot, battlefieldWidth, battlefieldHeight, this);
        enemyWaveDangerRevision = 0L;
        trackedOurEnergy = robot.getEnergy();
    }

    public void onBulletFired(Bullet bullet) {
        if (bullet == null) {
            return;
        }
        ourShotsFiredThisBattle++;
        Wave wave = waveManager.onBulletFired(bullet);
        bulletPowerHitRateTracker.onMyWaveFired(wave);
        trackedOurEnergy = robot.getEnergy();
    }

    public void updateWaves(Graphics2D g,
                            RenderState renderState) {
        waveManager.update();
        observationProfile.prepareWaveRenderState(this, waveManager.getEnemyWaves(), waveManager.getMyWaves());
        RenderState activeRenderState = renderState != null ? renderState : new RenderState(null);
        if (activeRenderState.renderDefaultWaveGraphics) {
            waveRenderer.render(
                    g,
                    robot.getTime(),
                    battlefieldWidth,
                    battlefieldHeight,
                    waveManager.getEnemyWaves(),
                    waveManager.getMyWaves(),
                    activeRenderState.enemyWaveRenderMode,
                    activeRenderState.myWaveRenderMode,
                    activeRenderState.highlightSelectedEnemyExpertTick);
        }
        pathRenderer.render(g, activeRenderState.pathOverlays);
    }

    /**
     * Called every tick to update per-tick state that evolves even before scans arrive.
     * Must be called before processing any scans for that tick.
     */
    public void updateTickState() {
        robotMotionTracker.update();
        enemyTracker.updateTick();
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        EnemyInfo.UpdateResult result = enemyTracker.onScannedRobot(
                e,
                observationProfile,
                robotMotionTracker.getHeadingDelta(),
                robotMotionTracker.getVelocityDelta(),
                robotMotionTracker.getAccelerationSign(),
                robotMotionTracker.getTicksSinceVelocityReversal(),
                robotMotionTracker.getTicksSinceDecel(),
                robotMotionTracker.getDistanceLastTicks(10),
                robotMotionTracker.getDistanceLastTicks(20),
                waveManager.didLastEnemyWaveHitRobot(),
                waveManager.getEnemyWaves(),
                enemyShotsFiredThisBattle);
        EnemyInfo enemy = enemyTracker.getEnemy();
        if (enemy != null) {
            precisePredictionTracker.onScannedRobot(captureRobotSnapshot(), enemy);
        }
        if (result.firedWave != null) {
            enemyShotsFiredThisBattle++;
            enemyBulletHitRateTracker.onEnemyWaveFired(result.firedWave);
            waveManager.addEnemyWave(result.firedWave);
        }
        Wave reconciledEnemyWave = waveManager.reconcilePendingEnemyBulletHit(e.getTime());
        if (reconciledEnemyWave != null) {
            enemyBulletHitRateTracker.onEnemyWaveHit(reconciledEnemyWave);
            enemyWaveDangerRevision++;
        }
    }

    public void onRobotDeath(RobotDeathEvent e) {
        Wave syntheticDeathWave = enemyTracker.onRobotDeath(
                e,
                observationProfile,
                waveManager.getEnemyWaves(),
                enemyShotsFiredThisBattle);
        if (syntheticDeathWave != null) {
            waveManager.addEnemyWave(syntheticDeathWave);
            enemyWaveDangerRevision++;
        }
    }

    public void onBulletHit(BulletHitEvent e) {
        Wave matchedWave = waveManager.validateAndRemoveMyWave(e);
        bulletPowerHitRateTracker.onMyWaveHit(matchedWave);
        double enemyEnergyBeforeHit = currentEnemyEnergyForScoring();
        scoreTracker.onBulletHit(e.getBullet().getPower(), enemyEnergyBeforeHit);
        enemyTracker.onBulletHit(e.getName(), e.getBullet().getPower());
        trackedOurEnergy = robot.getEnergy();
    }

    public void onBulletHitBullet(BulletHitBulletEvent e) {
        if (observationProfile.shouldPreserveMyWaveAfterBulletCollision()) {
            Wave matchedWave = waveManager.markMyWaveCollided(e);
            bulletPowerHitRateTracker.onMyWaveInvalidated(matchedWave);
        } else {
            Wave matchedWave = waveManager.validateAndRemoveMyWave(e);
            bulletPowerHitRateTracker.onMyWaveInvalidated(matchedWave);
        }
        Wave removedEnemyWave = waveManager.validateAndRemoveEnemyWave(e);
        if (removedEnemyWave != null) {
            enemyBulletHitRateTracker.onEnemyWaveInvalidated(removedEnemyWave);
            enemyWaveDangerRevision++;
        }
        trackedOurEnergy = robot.getEnergy();
    }

    public void onHitByBullet(HitByBulletEvent e) {
        double ourEnergyBeforeHit = trackedOurEnergy;
        Wave removedEnemyWave = waveManager.validateAndRemoveEnemyWave(e);
        if (removedEnemyWave != null) {
            enemyBulletHitRateTracker.onEnemyWaveHit(removedEnemyWave);
            enemyWaveDangerRevision++;
        }
        scoreTracker.onHitByBullet(e.getPower(), ourEnergyBeforeHit);
        enemyTracker.onHitByBullet(e.getName(), e.getPower());
        trackedOurEnergy = robot.getEnergy();
    }

    public void onHitRobot(HitRobotEvent e) {
        double enemyEnergyBeforeCollision = currentEnemyEnergyForScoring();
        double ourEnergyBeforeCollision = trackedOurEnergy;
        scoreTracker.onHitRobot(e.isMyFault(), enemyEnergyBeforeCollision, ourEnergyBeforeCollision);
        enemyTracker.onHitRobot(e.isMyFault(), e.getEnergy());
        trackedOurEnergy = robot.getEnergy();
    }

    public void onWin() {
        scoreTracker.onWin();
    }

    public void onDeath() {
        scoreTracker.onDeath();
    }

    public EnemyInfo getEnemy() {
        return enemyTracker.getEnemy();
    }

    public String getEnemyName() {
        return enemyTracker.getEnemyName();
    }

    public Saguaro getRobot() {
        return robot;
    }

    public RobotSnapshot captureRobotSnapshot() {
        if (robot == null) {
            throw new IllegalStateException("Info must be initialized before capturing robot state");
        }
        return new RobotSnapshot(
                robot.getX(),
                robot.getY(),
                robot.getHeadingRadians(),
                robot.getVelocity(),
                robot.getGunHeadingRadians(),
                robot.getGunHeat(),
                robot.getEnergy(),
                robot.getGunCoolingRate(),
                robot.getTime());
    }

    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfile;
    }

    public ObservationProfile getObservationProfile() {
        return observationProfile;
    }

    /**
     * Updates the round outcome profile reference only. Use during init before the enemy name
     * is known, when triggering a data load would be a no-op anyway.
     */
    public void setRoundOutcomeProfile(RoundOutcomeProfile roundOutcomeProfile) {
        if (roundOutcomeProfile == null) {
            throw new IllegalArgumentException("Round outcome profile must be non-null");
        }
        this.roundOutcomeProfile = roundOutcomeProfile;
        enemyTracker.setRoundOutcomeProfile(roundOutcomeProfile);
        scoreTracker.setRoundOutcomeProfile(roundOutcomeProfile);
    }

    /**
     * Updates the round outcome profile and triggers opponent data loading from disk.
     * Use when switching modes mid-battle or at round start (after enemy name is known),
     * so the new profile's persisted data is available immediately.
     */
    public void activateRoundOutcomeProfile(RoundOutcomeProfile roundOutcomeProfile) {
        setRoundOutcomeProfile(roundOutcomeProfile);
        enemyTracker.activateRoundOutcomeProfile(roundOutcomeProfile);
    }

    public void setObservationProfile(ObservationProfile observationProfile) {
        if (observationProfile == null) {
            throw new IllegalArgumentException("Observation profile must be non-null");
        }
        this.observationProfile = observationProfile;
    }

    public double getBattlefieldWidth() {
        return battlefieldWidth;
    }

    public double getBattlefieldHeight() {
        return battlefieldHeight;
    }

    public BattleDataStore getDataStore() {
        return dataStore;
    }

    public List<Wave> getMyWaves() {
        return waveManager.getMyWaves();
    }

    public List<Wave> getEnemyWaves() {
        return waveManager.getEnemyWaves();
    }

    public long getEnemyWaveDangerRevision() {
        return enemyWaveDangerRevision;
    }

    public double getOurRammingDamageOnEnemyThisRound() {
        return scoreTracker.getOurRammingDamageOnEnemyThisRound();
    }

    public double getEnemyRammingDamageOnUsThisRound() {
        return scoreTracker.getEnemyRammingDamageOnUsThisRound();
    }

    public double getOurBulletDamageOnEnemyThisRound() {
        return scoreTracker.getOurBulletDamageOnEnemyThisRound();
    }

    public double getEnemyBulletDamageOnUsThisRound() {
        return scoreTracker.getEnemyBulletDamageOnUsThisRound();
    }

    public double getOurCreditedDamageOnEnemyThisRound() {
        return scoreTracker.getOurCreditedDamageOnEnemyThisRound();
    }

    public double getEnemyCreditedDamageOnUsThisRound() {
        return scoreTracker.getEnemyCreditedDamageOnUsThisRound();
    }

    public double getOurScore() {
        return scoreTracker.getOurScore();
    }

    public double getOpponentScore() {
        return scoreTracker.getOpponentScore();
    }

    public int getRobotAccelerationSign() {
        return robotMotionTracker.getAccelerationSign();
    }

    public double getRobotHeadingDelta() {
        return robotMotionTracker.getHeadingDelta();
    }

    public double getRobotVelocityDelta() {
        return robotMotionTracker.getVelocityDelta();
    }

    public int getRobotTicksSinceVelocityReversal() {
        return robotMotionTracker.getTicksSinceVelocityReversal();
    }

    public int getRobotTicksSinceDecel() {
        return robotMotionTracker.getTicksSinceDecel();
    }

    public int getRobotLastNonZeroVelocitySign() {
        return robotMotionTracker.getLastNonZeroVelocitySign();
    }

    public int getRobotMotionHistorySize() {
        return robotMotionTracker.getMotionHistorySize();
    }

    public double getRobotMotionHistoryVelocity(int index) {
        return robotMotionTracker.getMotionHistoryVelocity(index);
    }

    public double getRobotMotionHistoryHeadingDelta(int index) {
        return robotMotionTracker.getMotionHistoryHeadingDelta(index);
    }

    public double getRobotDistanceLastTicks(int tickCount) {
        return robotMotionTracker.getDistanceLastTicks(tickCount);
    }

    public boolean didLastEnemyWaveHitRobot() {
        return waveManager.didLastEnemyWaveHitRobot();
    }

    public boolean didLastMyWaveHitOpponent() {
        return waveManager.didLastMyWaveHitOpponent();
    }

    public ReactiveOpponentPredictor createPerfectPredictionPredictor() {
        return precisePredictionTracker.createBestPredictor();
    }

    public BulletPowerHitRateTracker getBulletPowerHitRateTracker() {
        return bulletPowerHitRateTracker;
    }

    public EnemyBulletHitRateTracker getEnemyBulletHitRateTracker() {
        return enemyBulletHitRateTracker;
    }

    public void setScoreMaxTrackingEnabled(boolean enabled) {
        bulletPowerHitRateTracker.setTrackingEnabled(enabled);
        enemyBulletHitRateTracker.setTrackingEnabled(enabled);
    }

    public void setPrecisePredictionTrackingEnabled(boolean enabled) {
        precisePredictionTracker.setTrackingEnabled(enabled);
    }

    public void onMyWavePassedEnemy(Wave wave) {
        bulletPowerHitRateTracker.onMyWaveMiss(wave);
    }

    public void onEnemyWavePassedRobot(Wave wave) {
        enemyBulletHitRateTracker.onEnemyWaveMiss(wave);
    }

    public int getOurShotsFiredThisBattle() {
        return ourShotsFiredThisBattle;
    }

    public int getEnemyShotsFiredThisBattle() {
        return enemyShotsFiredThisBattle;
    }

    public double getTrackedOurEnergy() {
        return trackedOurEnergy;
    }

    private double currentEnemyEnergyForScoring() {
        EnemyInfo enemy = enemyTracker.getEnemy();
        if (enemy == null || !Double.isFinite(enemy.energy) || enemy.energy <= 0.0) {
            return 0.0;
        }
        return enemy.energy;
    }

    private static BattleDataStore requireDataStore(BattleDataStore dataStore) {
        if (dataStore == null) {
            throw new IllegalArgumentException("Info requires a non-null battle data store");
        }
        return dataStore;
    }
}
