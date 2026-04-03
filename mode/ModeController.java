package oog.mega.saguaro.mode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.ModeObservationProfile;
import oog.mega.saguaro.info.learning.NoOpLearningProfile;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.learning.ScoreMaxLearningProfile;
import oog.mega.saguaro.info.learning.ScoreMaxScoreHistoryProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.info.wave.WaveLog;
import oog.mega.saguaro.info.persistence.BulletPowerHitRateDataSet;
import oog.mega.saguaro.info.persistence.ModePerformanceDataSet;
import oog.mega.saguaro.info.persistence.WaveModelDataSet;
import oog.mega.saguaro.mode.antibasicsurfer.AntiBasicSurferRoundOutcomeProfile;
import oog.mega.saguaro.mode.antibasicsurfer.AntiBasicSurferMode;
import oog.mega.saguaro.mode.perfectprediction.PerfectPredictionMode;
import oog.mega.saguaro.mode.perfectprediction.PrecisePredictionProfile;
import oog.mega.saguaro.mode.scoremax.ScoreMaxMode;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerDataSet;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerMode;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerObservationProfile;
import oog.mega.saguaro.mode.shotdodger.ShotDodgerRoundOutcomeProfile;
import oog.mega.saguaro.mode.shotdodger.WavePoisonMode;
import oog.mega.saguaro.mode.shotdodger.WavePoisonRoundOutcomeProfile;
import oog.mega.saguaro.mode.shield.BulletShieldDataSet;
import oog.mega.saguaro.mode.shield.BulletShieldMode;
import oog.mega.saguaro.mode.shield.MovingBulletShieldDataSet;
import oog.mega.saguaro.render.RenderState;
import robocode.Bullet;
import robocode.BulletHitEvent;
import robocode.BulletHitBulletEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;

public final class ModeController {
    private final ShotDodgerObservationProfile shotDodgerObservationProfile = new ShotDodgerObservationProfile();
    private final RoundOutcomeProfile bulletShieldRoundOutcomeProfile = NoOpLearningProfile.INSTANCE;
    private final RoundOutcomeProfile movingBulletShieldRoundOutcomeProfile = NoOpLearningProfile.INSTANCE;
    private final RoundOutcomeProfile perfectPredictionRoundOutcomeProfile = NoOpLearningProfile.INSTANCE;
    private final RoundOutcomeProfile shotDodgerRoundOutcomeProfile = ShotDodgerRoundOutcomeProfile.INSTANCE;
    private final RoundOutcomeProfile wavePoisonRoundOutcomeProfile =
            new WavePoisonRoundOutcomeProfile(ModeId.WAVE_POISON);
    private final RoundOutcomeProfile wavePoisonShiftRoundOutcomeProfile =
            new WavePoisonRoundOutcomeProfile(ModeId.WAVE_POISON_SHIFT);
    private final RoundOutcomeProfile antiBasicSurferRoundOutcomeProfile =
            new AntiBasicSurferRoundOutcomeProfile(ModeId.ANTI_BASIC_SURFER);
    private final RoundOutcomeProfile scoreMaxRoundOutcomeProfile = ScoreMaxScoreHistoryProfile.INSTANCE;
    private final ModeObservationProfile observationProfile = new ModeObservationProfile(ScoreMaxLearningProfile.INSTANCE);
    private final BattleDataStore dataStore = new BattleDataStore();
    private final ModeRoundScoreTracker roundScoreTracker = new ModeRoundScoreTracker();
    private final ModeSelector modeSelector = new ModeSelector(roundScoreTracker);
    private final EnumMap<ModeId, Integer> modeInitializedRounds = new EnumMap<ModeId, Integer>(ModeId.class);
    private ShotDodgerMode shotDodgerMode;
    private WavePoisonMode wavePoisonMode;
    private WavePoisonMode wavePoisonShiftMode;
    private BulletShieldMode bulletShieldMode;
    private BulletShieldMode movingBulletShieldMode;
    private PerfectPredictionMode perfectPredictionMode;
    private AntiBasicSurferMode antiBasicSurferMode;
    private ScoreMaxMode scoreMaxMode;
    private BattleMode activeMode;
    private ModeId activeModeId = ModeId.SCORE_MAX;
    private final Set<BattleMode> modesUsedThisBattle = new LinkedHashSet<>();
    private final Set<ModeId> selectedModesThisBattle = new LinkedHashSet<>();
    private BattleServices services;
    private Info info;
    private int initializedRound = -1;
    private boolean opponentContextLoaded;
    private boolean pendingOpponentContextResolution;
    private boolean battleModeAnnouncementPrinted;
    private int remainingBulletShieldForgivenHits;
    private Saguaro colorAppliedRobot;
    private ModeId colorAppliedModeId;
    private ModeId selectedModeStreakModeId;
    private int selectedModeStreakRounds;

    public ModeController() {
        dataStore.registerDataSet(new BulletShieldDataSet());
        dataStore.registerDataSet(new MovingBulletShieldDataSet());
        dataStore.registerDataSet(new ShotDodgerDataSet());
        dataStore.registerDataSet(new ModePerformanceDataSet());
        dataStore.registerDataSet(new BulletPowerHitRateDataSet());
        dataStore.registerDataSet(new WaveModelDataSet());
    }

    public void startBattle() {
        dataStore.startBattle();
        PrecisePredictionProfile.startBattle();
        startRoundOutcomeProfile(bulletShieldRoundOutcomeProfile, null);
        startRoundOutcomeProfile(movingBulletShieldRoundOutcomeProfile, bulletShieldRoundOutcomeProfile);
        startRoundOutcomeProfile(scoreMaxRoundOutcomeProfile, bulletShieldRoundOutcomeProfile);
        startRoundOutcomeProfile(shotDodgerRoundOutcomeProfile, scoreMaxRoundOutcomeProfile);
        startRoundOutcomeProfile(wavePoisonRoundOutcomeProfile, shotDodgerRoundOutcomeProfile);
        startRoundOutcomeProfile(wavePoisonShiftRoundOutcomeProfile, wavePoisonRoundOutcomeProfile);
        startRoundOutcomeProfile(antiBasicSurferRoundOutcomeProfile, wavePoisonShiftRoundOutcomeProfile);
        resetModeInstances();
        modesUsedThisBattle.clear();
        selectedModesThisBattle.clear();
        modeInitializedRounds.clear();
        info = null;
        initializedRound = -1;
        opponentContextLoaded = false;
        pendingOpponentContextResolution = false;
        battleModeAnnouncementPrinted = false;
        remainingBulletShieldForgivenHits = 0;
        colorAppliedRobot = null;
        colorAppliedModeId = null;
        selectedModeStreakModeId = null;
        selectedModeStreakRounds = 0;
        roundScoreTracker.startBattle();
        setActiveMode(ModeId.SCORE_MAX, false);
    }

    public void init(Info info) {
        if (services == null) {
            services = new BattleServices(info, dataStore);
        }
        this.info = info;
        int roundNumber = info.getRobot().getRoundNum();
        if (roundNumber != initializedRound) {
            initializedRound = roundNumber;
            roundScoreTracker.startRound();
            ScoreMaxScoreHistoryProfile.INSTANCE.startRound();
            remainingBulletShieldForgivenHits = 0;
            if (!opponentContextLoaded) {
                pendingOpponentContextResolution = true;
                activateModeForRound(ModeId.BULLET_SHIELD, false);
            } else {
                pendingOpponentContextResolution = false;
                ModeId selectedMode = selectModeForRoundStart();
                activateModeForRound(selectedMode, true);
            }
        }
    }

    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfileFor(activeModeId);
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

    public static ModeId findSettledModeForPersistence() {
        return ModeSelector.findSettledMode();
    }

    public void saveCurrentBattle(Saguaro robot) {
        dataStore.requestDataSetSave(ModePerformanceDataSet.class);
        dataStore.requestDataSetSave(BulletPowerHitRateDataSet.class);
        dataStore.requestDataSetSave(WaveModelDataSet.class);
        for (BattleMode mode : modesUsedThisBattle) {
            mode.onBattleEnded(robot);
        }
        dataStore.saveRequestedData(robot);
        announceEndOfBattleWeights(robot);
    }

    public BattlePlan getPlan() {
        // Modes own behavior even when the opponent is currently unscanned or already dead.
        return activeMode.getPlan();
    }

    public void onFireResult(Bullet bullet, BattlePlan plan) {
        activeMode.onFireResult(bullet, plan);
    }

    public RenderState getRenderState() {
        return activeMode.getRenderState();
    }

    public String describeSkippedTurnDiagnostics() {
        if (activeMode == null) {
            return activeModeId.toString();
        }
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

    private void setActiveMode(ModeId nextModeId, boolean countAsBattleUsage) {
        activeModeId = nextModeId;
        BattleMode nextMode = info != null && services != null ? modeFor(nextModeId) : null;
        activeMode = nextMode;
        boolean scoreMaxTracking = nextModeId == ModeId.SCORE_MAX || nextModeId == ModeId.SHOT_DODGER;
        ScoreMaxScoreHistoryProfile.INSTANCE.setTrackingEnabled(scoreMaxTracking);
        if (info != null) {
            info.setScoreMaxTrackingEnabled(scoreMaxTracking);
            info.setPrecisePredictionTrackingEnabled(nextModeId == ModeId.PERFECT_PREDICTION);
        }
        if (nextModeId == ModeId.SHOT_DODGER || isWavePoisonVariant(nextModeId)) {
            observationProfile.setDelegate(shotDodgerObservationProfile);
        } else {
            observationProfile.setDelegate(ScoreMaxLearningProfile.INSTANCE);
        }
        observationProfile.setPolicy(observationPolicyFor(nextModeId));
        if (countAsBattleUsage && nextMode != null) {
            modesUsedThisBattle.add(nextMode);
            selectedModesThisBattle.add(nextModeId);
        }
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
        activateModeForRound(selectModeForBattle(), true);
    }

    private void activateModeForRound(ModeId selectedMode, boolean countAsBattleUsage) {
        ModeId previousModeId = activeModeId;
        if (countAsBattleUsage) {
            noteSelectedModeRound(selectedMode);
        }
        setActiveMode(selectedMode, countAsBattleUsage);
        roundScoreTracker.activateMode(selectedMode);
        info.activateRoundOutcomeProfile(roundOutcomeProfileFor(selectedMode));
        applyModeColorsIfNeeded();
        if (countAsBattleUsage && (!battleModeAnnouncementPrinted || selectedMode != previousModeId)) {
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

    private ModeId selectModeForRoundStart() {
        ModeId lockedMode = getLockedMode();
        if (lockedMode != null) {
            return lockedMode;
        }
        if (!canSwitchAwayFromActiveMode()) {
            return activeModeId;
        }
        ModeId[] selectableModes = selectableModes(true);
        if (modeSelector.isModeDisqualified(activeModeId, selectableModes)) {
            return modeSelector.selectMode(selectableModes(false));
        }
        return activeModeId;
    }

    private ModeId selectModeForBattle() {
        ModeId lockedMode = getLockedMode();
        if (lockedMode != null) {
            return lockedMode;
        }
        return modeSelector.selectMode(selectableModes(false));
    }

    private ModeId[] selectableModes() {
        return selectableModes(false);
    }

    private ModeId[] selectableModes(boolean retainActiveAntiBasicSurferBelowThreshold) {
        List<ModeId> selectable = new ArrayList<>();
        for (ModeId modeId : ModeId.values()) {
            if (isModeSelectable(modeId, retainActiveAntiBasicSurferBelowThreshold)) {
                selectable.add(modeId);
            }
        }
        return selectable.toArray(new ModeId[0]);
    }

    private boolean isModeSelectable(ModeId modeId, boolean retainActiveAntiBasicSurferBelowThreshold) {
        if (modeId == null) {
            return false;
        }
        if (modeId == ModeId.BULLET_SHIELD || isWavePoisonVariant(modeId)
                || modeId == ModeId.ANTI_BASIC_SURFER) {
            if (hasUsedAnyModeOtherThan(modeId)) {
                return false;
            }
        } else if (modeId == ModeId.MOVING_BULLET_SHIELD) {
            if (hasUsedAnyModeOutside(ModeId.BULLET_SHIELD, ModeId.MOVING_BULLET_SHIELD)) {
                return false;
            }
        }
        if (modeId == ModeId.ANTI_BASIC_SURFER
                && !modeSelector.meetsSelectionRequirements(modeId)
                && !(retainActiveAntiBasicSurferBelowThreshold && modeId == activeModeId)) {
            return false;
        }
        return true;
    }

    private boolean canSwitchAwayFromActiveMode() {
        return activeModeId != selectedModeStreakModeId
                || selectedModeStreakRounds >= BotConfig.ModeSelection.MIN_ROUNDS_BEFORE_SWITCH;
    }

    private void noteSelectedModeRound(ModeId selectedMode) {
        if (selectedMode == null) {
            return;
        }
        if (selectedMode == selectedModeStreakModeId) {
            selectedModeStreakRounds++;
            return;
        }
        selectedModeStreakModeId = selectedMode;
        selectedModeStreakRounds = 1;
    }

    private boolean hasUsedAnyModeOtherThan(ModeId allowedMode) {
        for (ModeId usedMode : selectedModesThisBattle) {
            if (usedMode != allowedMode) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUsedAnyModeOutside(ModeId firstAllowedMode, ModeId secondAllowedMode) {
        for (ModeId usedMode : selectedModesThisBattle) {
            if (usedMode != firstAllowedMode && usedMode != secondAllowedMode) {
                return true;
            }
        }
        return false;
    }

    private BattleMode modeFor(ModeId modeId) {
        if (modeId == ModeId.SCORE_MAX) {
            if (scoreMaxMode == null) {
                scoreMaxMode = new ScoreMaxMode();
            }
            ensureModeInitialized(modeId, scoreMaxMode);
            return scoreMaxMode;
        }
        if (modeId == ModeId.BULLET_SHIELD) {
            if (bulletShieldMode == null) {
                bulletShieldMode =
                        new BulletShieldMode(ModeId.BULLET_SHIELD, false, BulletShieldDataSet.class);
            }
            ensureModeInitialized(modeId, bulletShieldMode);
            return bulletShieldMode;
        }
        if (modeId == ModeId.MOVING_BULLET_SHIELD) {
            if (movingBulletShieldMode == null) {
                movingBulletShieldMode =
                        new BulletShieldMode(ModeId.MOVING_BULLET_SHIELD, true, MovingBulletShieldDataSet.class);
            }
            ensureModeInitialized(modeId, movingBulletShieldMode);
            return movingBulletShieldMode;
        }
        if (modeId == ModeId.PERFECT_PREDICTION) {
            if (perfectPredictionMode == null) {
                perfectPredictionMode = new PerfectPredictionMode();
            }
            ensureModeInitialized(modeId, perfectPredictionMode);
            return perfectPredictionMode;
        }
        if (modeId == ModeId.SHOT_DODGER) {
            if (shotDodgerMode == null) {
                shotDodgerMode = new ShotDodgerMode(shotDodgerObservationProfile);
            }
            ensureModeInitialized(modeId, shotDodgerMode);
            return shotDodgerMode;
        }
        if (modeId == ModeId.WAVE_POISON) {
            if (wavePoisonMode == null) {
                wavePoisonMode =
                        new WavePoisonMode(ModeId.WAVE_POISON, shotDodgerObservationProfile, 1, 0.5, -0.5);
            }
            ensureModeInitialized(modeId, wavePoisonMode);
            return wavePoisonMode;
        }
        if (modeId == ModeId.WAVE_POISON_SHIFT) {
            if (wavePoisonShiftMode == null) {
                wavePoisonShiftMode =
                        new WavePoisonMode(ModeId.WAVE_POISON_SHIFT, shotDodgerObservationProfile, 2, 0.5, -0.5);
            }
            ensureModeInitialized(modeId, wavePoisonShiftMode);
            return wavePoisonShiftMode;
        }
        if (modeId == ModeId.ANTI_BASIC_SURFER) {
            if (antiBasicSurferMode == null) {
                antiBasicSurferMode = new AntiBasicSurferMode();
            }
            ensureModeInitialized(modeId, antiBasicSurferMode);
            return antiBasicSurferMode;
        }
        throw new IllegalArgumentException("Unsupported mode id " + modeId);
    }

    private void ensureModeInitialized(ModeId modeId, BattleMode mode) {
        if (modeId == null || mode == null || info == null || services == null) {
            return;
        }
        int roundNumber = info.getRobot().getRoundNum();
        Integer initializedModeRound = modeInitializedRounds.get(modeId);
        if (initializedModeRound != null && initializedModeRound.intValue() == roundNumber) {
            return;
        }
        mode.init(info, services);
        modeInitializedRounds.put(modeId, roundNumber);
    }

    private RoundOutcomeProfile roundOutcomeProfileFor(ModeId modeId) {
        if (modeId == ModeId.BULLET_SHIELD) {
            return bulletShieldRoundOutcomeProfile;
        }
        if (modeId == ModeId.MOVING_BULLET_SHIELD) {
            return movingBulletShieldRoundOutcomeProfile;
        }
        if (modeId == ModeId.PERFECT_PREDICTION) {
            return perfectPredictionRoundOutcomeProfile;
        }
        if (modeId == ModeId.SHOT_DODGER) {
            return shotDodgerRoundOutcomeProfile;
        }
        if (modeId == ModeId.WAVE_POISON) {
            return wavePoisonRoundOutcomeProfile;
        }
        if (modeId == ModeId.WAVE_POISON_SHIFT) {
            return wavePoisonShiftRoundOutcomeProfile;
        }
        if (modeId == ModeId.ANTI_BASIC_SURFER) {
            return antiBasicSurferRoundOutcomeProfile;
        }
        if (modeId == ModeId.SCORE_MAX) {
            return scoreMaxRoundOutcomeProfile;
        }
        throw new IllegalArgumentException("Unsupported mode id " + modeId);
    }

    private static ModeObservationPolicy observationPolicyFor(ModeId modeId) {
        if (modeId == ModeId.BULLET_SHIELD || modeId == ModeId.MOVING_BULLET_SHIELD) {
            return ModeObservationPolicy.TARGETING_ONLY;
        }
        if (modeId == ModeId.PERFECT_PREDICTION) {
            return ModeObservationPolicy.MOVEMENT_UPDATE_ONLY;
        }
        if (modeId == ModeId.ANTI_BASIC_SURFER) {
            return ModeObservationPolicy.MOVEMENT_FULL_TARGETING_READ_ONLY;
        }
        if (modeId == ModeId.SHOT_DODGER || isWavePoisonVariant(modeId)) {
            return ModeObservationPolicy.SHOT_DODGER_EXPERT_ONLY;
        }
        return ModeObservationPolicy.FULL;
    }

    private void resetModeInstances() {
        shotDodgerMode = null;
        wavePoisonMode = null;
        wavePoisonShiftMode = null;
        bulletShieldMode = null;
        movingBulletShieldMode = null;
        perfectPredictionMode = null;
        antiBasicSurferMode = null;
        scoreMaxMode = null;
        activeMode = null;
    }

    private ModeId getLockedMode() {
        ModeId configuredLockedMode = getLockedModeFromConfig();
        if (configuredLockedMode != null) {
            return configuredLockedMode;
        }
        return dataStore != null ? dataStore.getPersistedLockedMode() : null;
    }

    private ModeId getLockedModeFromConfig() {
        String lockedModeLabel = oog.mega.saguaro.BotConfig.ModeSelection.LOCKED_MODE;
        if (lockedModeLabel == null || lockedModeLabel.isEmpty()) {
            return null;
        }
        for (ModeId mode : ModeId.values()) {
            if (mode.label().equals(lockedModeLabel)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Invalid locked mode in config: " + lockedModeLabel);
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
        if (modeId == ModeId.SHOT_DODGER || isWavePoisonVariant(modeId)) {
            info.getRobot().out.println(ShotDodgerObservationProfile.describeBootstrapStatus());
        }
    }

    private void announceEndOfBattleWeights(Saguaro robot) {
        if (!WaveLog.isTargetingModelDefault()) {
            printWaveModelDelta(robot, "Targeting Delta:", WaveLog.getTargetingModelDeltaLines());
        }
        if (!WaveLog.isMovementModelDefault()) {
            printWaveModelDelta(robot, "Movement Delta:", WaveLog.getMovementModelDeltaLines());
        }
    }

    private void printWaveModelDelta(Saguaro robot, String header, List<String> lines) {
        robot.out.println(header);
        if (lines == null || lines.isEmpty()) {
            robot.out.println("  none");
            return;
        }
        for (String line : lines) {
            robot.out.println("  " + line);
        }
    }

    private static boolean isShieldMode(ModeId modeId) {
        return modeId == ModeId.BULLET_SHIELD || modeId == ModeId.MOVING_BULLET_SHIELD;
    }

    private static boolean isWavePoisonVariant(ModeId modeId) {
        return modeId == ModeId.WAVE_POISON || modeId == ModeId.WAVE_POISON_SHIFT;
    }
}
