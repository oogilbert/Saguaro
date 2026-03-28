package oog.mega.saguaro.mode;

import java.util.LinkedHashSet;
import java.util.Set;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationProfile;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.learning.ScoreMaxLearningProfile;
import oog.mega.saguaro.info.learning.ScoreMaxScoreHistoryProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.info.wave.WaveLog;
import oog.mega.saguaro.info.persistence.BulletPowerHitRateDataSet;
import oog.mega.saguaro.info.persistence.ModePerformanceDataSet;
import oog.mega.saguaro.info.persistence.WaveLogModelDataSet;
import oog.mega.saguaro.mode.perfectprediction.PerfectPredictionMode;
import oog.mega.saguaro.mode.perfectprediction.PrecisePredictionProfile;
import oog.mega.saguaro.mode.scoremax.ScoreMaxMode;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerDataSet;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerMode;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerObservationProfile;
import oog.mega.saguaro.mode.shield.BulletShieldDataSet;
import oog.mega.saguaro.mode.shield.BulletShieldMode;
import oog.mega.saguaro.mode.shield.MovingBulletShieldDataSet;
import oog.mega.saguaro.render.RenderState;
import robocode.BulletHitEvent;
import robocode.BulletHitBulletEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;

public final class ModeController {
    private final ShotDodgerMode shotDodgerMode = new ShotDodgerMode();
    private final BulletShieldMode bulletShieldMode =
            new BulletShieldMode(ModeId.BULLET_SHIELD, false, BulletShieldDataSet.class);
    private final BulletShieldMode movingBulletShieldMode =
            new BulletShieldMode(ModeId.MOVING_BULLET_SHIELD, true, MovingBulletShieldDataSet.class);
    private final PerfectPredictionMode perfectPredictionMode = new PerfectPredictionMode();
    private final ScoreMaxMode scoreMaxMode = new ScoreMaxMode();
    private final ModeObservationProfile observationProfile = new ModeObservationProfile(ScoreMaxLearningProfile.INSTANCE);
    private final BattleDataStore dataStore = new BattleDataStore();
    private final ModeRoundScoreTracker roundScoreTracker = new ModeRoundScoreTracker();
    private final ModeSelector modeSelector = new ModeSelector(roundScoreTracker);
    private BattleMode activeMode = scoreMaxMode;
    private ModeId activeModeId = ModeId.SCORE_MAX;
    private final Set<BattleMode> modesUsedThisBattle = new LinkedHashSet<>();
    private BattleServices services;
    private Info info;
    private int initializedRound = -1;
    private boolean opponentContextLoaded;
    private boolean pendingOpponentContextResolution;
    private ModeProgressionStage progressionStage = ModeProgressionStage.NON_MOVING_SHIELD;
    private boolean battleModeAnnouncementPrinted;
    private int remainingBulletShieldForgivenHits;
    private Saguaro colorAppliedRobot;
    private ModeId colorAppliedModeId;

    public ModeController() {
        dataStore.registerDataSet(new BulletShieldDataSet());
        dataStore.registerDataSet(new MovingBulletShieldDataSet());
        dataStore.registerDataSet(new ShotDodgerDataSet());
        dataStore.registerDataSet(new ModePerformanceDataSet());
        dataStore.registerDataSet(new BulletPowerHitRateDataSet());
        dataStore.registerDataSet(new WaveLogModelDataSet());
    }

    public void startBattle() {
        dataStore.startBattle();
        PrecisePredictionProfile.startBattle();
        startRoundOutcomeProfile(bulletShieldMode.getRoundOutcomeProfile(), null);
        startRoundOutcomeProfile(movingBulletShieldMode.getRoundOutcomeProfile(), bulletShieldMode.getRoundOutcomeProfile());
        startRoundOutcomeProfile(scoreMaxMode.getRoundOutcomeProfile(), bulletShieldMode.getRoundOutcomeProfile());
        startRoundOutcomeProfile(shotDodgerMode.getRoundOutcomeProfile(), scoreMaxMode.getRoundOutcomeProfile());
        modesUsedThisBattle.clear();
        initializedRound = -1;
        opponentContextLoaded = false;
        pendingOpponentContextResolution = false;
        progressionStage = ModeProgressionStage.NON_MOVING_SHIELD;
        battleModeAnnouncementPrinted = false;
        remainingBulletShieldForgivenHits = 0;
        colorAppliedRobot = null;
        colorAppliedModeId = null;
        roundScoreTracker.startBattle();
        setActiveMode(ModeId.SCORE_MAX);
    }

    public void init(Info info) {
        if (services == null) {
            services = new BattleServices(info, dataStore);
        }
        this.info = info;
        modeSelector.setInfo(info);
        shotDodgerMode.init(info, services);
        bulletShieldMode.init(info, services);
        movingBulletShieldMode.init(info, services);
        perfectPredictionMode.init(info, services);
        scoreMaxMode.init(info, services);
        int roundNumber = info.getRobot().getRoundNum();
        if (roundNumber != initializedRound) {
            initializedRound = roundNumber;
            roundScoreTracker.startRound();
            ScoreMaxScoreHistoryProfile.INSTANCE.startRound();
            remainingBulletShieldForgivenHits = 0;
            if (!opponentContextLoaded) {
                pendingOpponentContextResolution = true;
                activateModeForRound(ModeId.BULLET_SHIELD);
            } else {
                pendingOpponentContextResolution = false;
                activateModeForRound(chooseModeForSwitch());
            }
        }
    }

    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return activeMode.getRoundOutcomeProfile();
    }

    public ObservationProfile getObservationProfile() {
        return observationProfile;
    }

    public BattleDataStore getDataStore() {
        return dataStore;
    }

    public static String describeModeEstimate(ModeId modeId) {
        return ModeSelector.describeModeEstimate(modeId);
    }

    public void saveCurrentBattle(Saguaro robot) {
        dataStore.requestDataSetSave(ModePerformanceDataSet.class);
        dataStore.requestDataSetSave(BulletPowerHitRateDataSet.class);
        dataStore.requestDataSetSave(WaveLogModelDataSet.class);
        for (BattleMode mode : modesUsedThisBattle) {
            mode.onBattleEnded(robot);
        }
        dataStore.saveRequestedData(robot);
    }

    public BattlePlan getPlan() {
        // Modes own behavior even when the opponent is currently unscanned or already dead.
        return activeMode.getPlan();
    }

    public RenderState getRenderState() {
        return activeMode.getRenderState();
    }

    public String describeSkippedTurnDiagnostics() {
        return activeModeId + " " + activeMode.describeSkippedTurnDiagnostics();
    }

    public void onBulletHitBullet(BulletHitBulletEvent event) {
        activeMode.onBulletHitBullet(event);
    }

    public void onBulletHit(BulletHitEvent event) {
        activeMode.onBulletHit(event);
        double enemyEnergyBeforeHit = currentEnemyEnergyBeforeDamage();
        roundScoreTracker.onBulletHit(event.getBullet().getPower(), enemyEnergyBeforeHit);
        ScoreMaxScoreHistoryProfile.INSTANCE.onBulletHit(event.getBullet().getPower(), enemyEnergyBeforeHit);
    }

    public void onHitByBullet(HitByBulletEvent event) {
        activeMode.onHitByBullet(event);
        boolean forgivenThisHit = isShieldMode(activeModeId) && remainingBulletShieldForgivenHits > 0;
        if (forgivenThisHit) {
            remainingBulletShieldForgivenHits--;
        }
        double ourEnergyBeforeHit = info.getTrackedOurEnergy();
        roundScoreTracker.onHitByBullet(event.getPower(), ourEnergyBeforeHit, forgivenThisHit);
        ScoreMaxScoreHistoryProfile.INSTANCE.onHitByBullet(event.getPower(), ourEnergyBeforeHit);
    }

    public void onHitRobot(HitRobotEvent event) {
        activeMode.onHitRobot(event);
        double enemyEnergyBeforeCollision = currentEnemyEnergyBeforeDamage();
        double ourEnergyBeforeCollision = info.getTrackedOurEnergy();
        roundScoreTracker.onHitRobot(event.isMyFault(), enemyEnergyBeforeCollision, ourEnergyBeforeCollision);
        ScoreMaxScoreHistoryProfile.INSTANCE.onHitRobot(
                event.isMyFault(),
                enemyEnergyBeforeCollision,
                ourEnergyBeforeCollision);
    }

    public void onScannedRobot(ScannedRobotEvent event) {
        if (pendingOpponentContextResolution) {
            resolvePendingOpponentContext(event);
        }
        activeMode.onScannedRobot(event);
    }

    public void onRobotDeath(RobotDeathEvent event) {
        activeMode.onRobotDeath(event);
    }

    public void onRoundEnded() {
        roundScoreTracker.onRoundEnded(info.getOurScore(), info.getOpponentScore());
    }

    public void onWin() {
        roundScoreTracker.onRoundResult(info.getOurScore(), info.getOpponentScore());
        ScoreMaxScoreHistoryProfile.INSTANCE.onWin();
    }

    public void onDeath() {
        roundScoreTracker.onRoundResult(info.getOurScore(), info.getOpponentScore());
        ScoreMaxScoreHistoryProfile.INSTANCE.onDeath();
    }

    private void setActiveMode(ModeId nextModeId) {
        BattleMode nextMode = modeFor(nextModeId);
        activeModeId = nextModeId;
        activeMode = nextMode;
        boolean scoreMaxTracking = nextModeId == ModeId.SCORE_MAX || nextModeId == ModeId.SHOT_DODGER;
        ScoreMaxScoreHistoryProfile.INSTANCE.setTrackingEnabled(scoreMaxTracking);
        if (info != null) {
            info.setScoreMaxTrackingEnabled(scoreMaxTracking);
        }
        if (nextModeId == ModeId.SHOT_DODGER) {
            observationProfile.setDelegate(shotDodgerMode.getObservationProfile());
        } else {
            observationProfile.setDelegate(ScoreMaxLearningProfile.INSTANCE);
        }
        observationProfile.setPolicy(nextMode.getObservationPolicy());
        modesUsedThisBattle.add(nextMode);
    }

    private double currentEnemyEnergyBeforeDamage() {
        if (info == null || info.getEnemy() == null) {
            return 0.0;
        }
        double enemyEnergy = info.getEnemy().energy;
        if (!Double.isFinite(enemyEnergy) || enemyEnergy <= 0.0) {
            return 0.0;
        }
        return enemyEnergy;
    }

    private void startRoundOutcomeProfile(RoundOutcomeProfile profile, RoundOutcomeProfile otherProfile) {
        if (profile == null || profile == otherProfile) {
            return;
        }
        profile.startBattle();
    }

    private void resolvePendingOpponentContext(ScannedRobotEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Mode controller requires a non-null scan event to resolve opponent context");
        }
        dataStore.ensureOpponentDataLoaded(info.getRobot(), event.getName());
        if (!opponentContextLoaded) {
            remainingBulletShieldForgivenHits =
                    dataStore.isFirstBattleForTrackedOpponent() && info.getRobot().getRoundNum() == 0 ? 2 : 0;
        }
        opponentContextLoaded = true;
        pendingOpponentContextResolution = false;
        activateModeForRound(chooseOpeningMode());
    }

    private void activateModeForRound(ModeId selectedMode) {
        ModeId previousModeId = activeModeId;
        setActiveMode(selectedMode);
        roundScoreTracker.activateMode(selectedMode);
        progressionStage = progressionStageAfterSelection(selectedMode, progressionStage);
        info.activateRoundOutcomeProfile(activeMode.getRoundOutcomeProfile());
        applyModeColorsIfNeeded();
        if (!battleModeAnnouncementPrinted || selectedMode != previousModeId) {
            announceMode(previousModeId, selectedMode);
        }
    }

    private void applyModeColorsIfNeeded() {
        Saguaro robot = info.getRobot();
        if (robot != colorAppliedRobot || activeModeId != colorAppliedModeId) {
            activeMode.applyColors(robot);
            colorAppliedRobot = robot;
            colorAppliedModeId = activeModeId;
        }
    }

    private ModeId chooseModeForSwitch() {
        if (progressionStage == ModeProgressionStage.NON_MOVING_SHIELD) {
            return modeSelector.chooseModeForSwitch(
                    activeModeId,
                    new ModeId[] {
                            ModeId.BULLET_SHIELD,
                            ModeId.MOVING_BULLET_SHIELD,
                            ModeId.SCORE_MAX,
                            ModeId.PERFECT_PREDICTION,
                            ModeId.SHOT_DODGER
                    });
        }
        if (progressionStage == ModeProgressionStage.MOVING_SHIELD) {
            return modeSelector.chooseModeForSwitch(
                    activeModeId,
                    new ModeId[] {
                            ModeId.MOVING_BULLET_SHIELD,
                            ModeId.SCORE_MAX,
                            ModeId.PERFECT_PREDICTION,
                            ModeId.SHOT_DODGER
                    });
        }
        return modeSelector.chooseModeForSwitch(
                activeModeId,
                new ModeId[] {ModeId.SCORE_MAX, ModeId.PERFECT_PREDICTION, ModeId.SHOT_DODGER});
    }

    private ModeId chooseOpeningMode() {
        return modeSelector.chooseOpeningMode(new ModeId[] {
                ModeId.BULLET_SHIELD,
                ModeId.MOVING_BULLET_SHIELD,
                ModeId.SCORE_MAX,
                ModeId.PERFECT_PREDICTION,
                ModeId.SHOT_DODGER
        });
    }

    private BattleMode modeFor(ModeId modeId) {
        if (modeId == ModeId.SCORE_MAX) {
            return scoreMaxMode;
        }
        if (modeId == ModeId.BULLET_SHIELD) {
            return bulletShieldMode;
        }
        if (modeId == ModeId.MOVING_BULLET_SHIELD) {
            return movingBulletShieldMode;
        }
        if (modeId == ModeId.PERFECT_PREDICTION) {
            return perfectPredictionMode;
        }
        if (modeId == ModeId.SHOT_DODGER) {
            return shotDodgerMode;
        }
        throw new IllegalArgumentException("Unsupported mode id " + modeId);
    }

    private void announceMode(ModeId fromModeId, ModeId toModeId) {
        if (!battleModeAnnouncementPrinted) {
            info.getRobot().out.println("Battle mode: " + toModeId.displayName());
            battleModeAnnouncementPrinted = true;
        } else {
            info.getRobot().out.println("Mode switch: " + fromModeId.displayName() + " -> " + toModeId.displayName());
        }
        announceWeightsIfApplicable(toModeId);
    }

    private void announceWeightsIfApplicable(ModeId modeId) {
        if (modeId == ModeId.SCORE_MAX) {
            info.getRobot().out.println("Targeting Weights: " + WaveLog.getTargetingModelSummary());
            info.getRobot().out.println("Movement Weights: " + WaveLog.getMovementModelSummary());
        } else if (modeId == ModeId.SHOT_DODGER) {
            info.getRobot().out.println(ShotDodgerObservationProfile.describeBootstrapStatus());
        }
    }

    private static ModeProgressionStage progressionStageAfterSelection(ModeId selectedMode,
                                                                       ModeProgressionStage currentStage) {
        if (selectedMode == ModeId.MOVING_BULLET_SHIELD) {
            return ModeProgressionStage.MOVING_SHIELD;
        }
        if (selectedMode != ModeId.BULLET_SHIELD) {
            return ModeProgressionStage.OTHER_MODES;
        }
        return currentStage;
    }

    private static boolean isShieldMode(ModeId modeId) {
        return modeId == ModeId.BULLET_SHIELD || modeId == ModeId.MOVING_BULLET_SHIELD;
    }

    private enum ModeProgressionStage {
        NON_MOVING_SHIELD,
        MOVING_SHIELD,
        OTHER_MODES
    }
}
