package oog.mega.saguaro.mode;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationProfile;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.learning.ScoreMaxLearningProfile;
import oog.mega.saguaro.info.learning.ScoreMaxScoreHistoryProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.info.persistence.BulletPowerHitRateDataSet;
import oog.mega.saguaro.info.persistence.ModePerformanceDataSet;
import oog.mega.saguaro.info.persistence.ScoreHistoryDataSet;
import oog.mega.saguaro.info.persistence.WaveLogModelDataSet;
import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import oog.mega.saguaro.mode.perfectprediction.PerfectPredictionMode;
import oog.mega.saguaro.mode.perfectprediction.PrecisePredictionProfile;
import oog.mega.saguaro.mode.scoremax.ScoreMaxMode;
import oog.mega.saguaro.mode.shield.BulletShieldDataSet;
import oog.mega.saguaro.mode.shield.BulletShieldMode;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import robocode.BulletHitEvent;
import robocode.BulletHitBulletEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;

public final class ModeController {
    private static final double MODE_SELECTION_CONFIDENCE_SCALE = 4.0;
    private static final double POSTERIOR_SCORE_UNIT = 24.0;
    private static final double MODE_PRIOR_SCORE = 48.0;
    private static final double MODE_PRIOR_FADE_SCORE = 500.0;
    private static final double MODE_UNCERTAINTY_PRIOR_ALPHA = 1.0;
    private static final double MODE_UNCERTAINTY_PRIOR_BETA = 1.0;
    private static final double MODE_UNCERTAINTY_PRIOR_EPSILON = 1e-6;
    private static final double MODE_SETTLED_CI_WIDTH = 0.10;

    public static final class DebugLine {
        public final double x1;
        public final double y1;
        public final double x2;
        public final double y2;
        public final Color color;
        public final float strokeWidth;

        public DebugLine(double x1, double y1, double x2, double y2, Color color) {
            this(x1, y1, x2, y2, color, 1.0f);
        }

        public DebugLine(double x1, double y1, double x2, double y2, Color color, float strokeWidth) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.color = color;
            this.strokeWidth = strokeWidth;
        }
    }

    public static final class RenderState {
        public final CandidatePath selectedPath;
        public final List<CandidatePath> selectedSafeSpotPaths;
        public final List<PathWaveIntersection> selectedPathIntersections;
        public final List<DebugLine> debugLines;
        public final boolean renderDefaultWaveGraphics;

        public RenderState(CandidatePath selectedPath,
                           List<CandidatePath> selectedSafeSpotPaths,
                           List<PathWaveIntersection> selectedPathIntersections) {
            this(selectedPath, selectedSafeSpotPaths, selectedPathIntersections, null, true);
        }

        public RenderState(CandidatePath selectedPath,
                           List<CandidatePath> selectedSafeSpotPaths,
                           List<PathWaveIntersection> selectedPathIntersections,
                           List<DebugLine> debugLines) {
            this(selectedPath, selectedSafeSpotPaths, selectedPathIntersections, debugLines, true);
        }

        public RenderState(CandidatePath selectedPath,
                           List<CandidatePath> selectedSafeSpotPaths,
                           List<PathWaveIntersection> selectedPathIntersections,
                           List<DebugLine> debugLines,
                           boolean renderDefaultWaveGraphics) {
            this.selectedPath = selectedPath;
            this.selectedSafeSpotPaths =
                    selectedSafeSpotPaths != null ? selectedSafeSpotPaths : Collections.<CandidatePath>emptyList();
            this.selectedPathIntersections =
                    selectedPathIntersections != null
                            ? selectedPathIntersections
                            : Collections.<PathWaveIntersection>emptyList();
            this.debugLines = debugLines != null ? debugLines : Collections.<DebugLine>emptyList();
            this.renderDefaultWaveGraphics = renderDefaultWaveGraphics;
        }
    }

    private final BulletShieldMode bulletShieldMode = new BulletShieldMode();
    private final PerfectPredictionMode perfectPredictionMode = new PerfectPredictionMode();
    private final ScoreMaxMode scoreMaxMode = new ScoreMaxMode();
    private final ModeObservationProfile observationProfile =
            new ModeObservationProfile(ScoreMaxLearningProfile.INSTANCE);
    private final BattleDataStore dataStore = new BattleDataStore();
    private final ModeRoundScoreTracker roundScoreTracker = new ModeRoundScoreTracker();
    private BattleMode activeMode = scoreMaxMode;
    private ModeId activeModeId = ModeId.SCORE_MAX;
    private final Set<BattleMode> modesUsedThisBattle = new LinkedHashSet<>();
    private BattleServices services;
    private Info info;
    private int initializedRound = -1;
    private boolean roundSelectionResolved;
    private boolean opponentContextLoaded;
    private boolean pendingOpponentContextResolution;
    private boolean bulletShieldRetiredForBattle;
    private boolean battleModeAnnouncementPrinted;
    private boolean battleOpeningModeResolved;
    private int remainingBulletShieldForgivenHits;

    public ModeController() {
        dataStore.registerDataSet(new BulletShieldDataSet());
        dataStore.registerDataSet(new ModePerformanceDataSet());
        dataStore.registerDataSet(new BulletPowerHitRateDataSet());
        dataStore.registerDataSet(new WaveLogModelDataSet());
    }

    public void startBattle() {
        dataStore.startBattle();
        PrecisePredictionProfile.startBattle();
        startRoundOutcomeProfile(bulletShieldMode.getRoundOutcomeProfile(), null);
        startRoundOutcomeProfile(scoreMaxMode.getRoundOutcomeProfile(), bulletShieldMode.getRoundOutcomeProfile());
        modesUsedThisBattle.clear();
        initializedRound = -1;
        roundSelectionResolved = false;
        opponentContextLoaded = false;
        pendingOpponentContextResolution = false;
        bulletShieldRetiredForBattle = false;
        battleModeAnnouncementPrinted = false;
        battleOpeningModeResolved = false;
        remainingBulletShieldForgivenHits = 0;
        roundScoreTracker.startBattle();
        setActiveMode(ModeId.SCORE_MAX);
    }

    public void init(Info info) {
        if (info == null) {
            throw new IllegalArgumentException("Mode controller requires non-null info");
        }
        if (services == null || this.info != info) {
            services = new BattleServices(info, dataStore);
        }
        this.info = info;
        bulletShieldMode.init(info, services);
        perfectPredictionMode.init(info, services);
        scoreMaxMode.init(info, services);
        int roundNumber = info.getRobot().getRoundNum();
        if (roundNumber != initializedRound) {
            initializedRound = roundNumber;
            roundScoreTracker.startRound();
            ScoreMaxScoreHistoryProfile.INSTANCE.startRound();
            remainingBulletShieldForgivenHits = 0;
            pendingOpponentContextResolution = !opponentContextLoaded;
            ModeId preselectedMode = choosePreScanRoundMode();
            roundSelectionResolved = true;
            activateModeForRound(preselectedMode);
        }
        info.activateRoundOutcomeProfile(activeMode.getRoundOutcomeProfile());
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
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getCombinedStats(modeId);
        ModePosterior estimate = estimateMode(modeId, stats);
        return String.format(
                Locale.US,
                "share %.1f%% [%.1f%%, %.1f%%], raw %.0f-%.0f",
                estimate.posteriorMean * 100.0,
                estimate.lowerBound * 100.0,
                estimate.upperBound * 100.0,
                stats.totalOurScore,
                stats.totalOpponentScore);
    }

    public void saveCurrentBattle(Saguaro robot) {
        if (robot == null) {
            throw new IllegalArgumentException("Mode controller requires non-null robot to save battle data");
        }
        dataStore.requestDataSetSave(ScoreHistoryDataSet.class);
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

    public void applyColors(Saguaro robot) {
        activeMode.applyColors(robot);
    }

    public void onBulletHitBullet(BulletHitBulletEvent event) {
        activeMode.onBulletHitBullet(event);
    }

    public void onBulletHit(BulletHitEvent event) {
        activeMode.onBulletHit(event);
        double enemyEnergyBeforeHit = currentEnemyEnergyBeforeDamage();
        roundScoreTracker.onBulletHit(event.getBullet().getPower(), enemyEnergyBeforeHit);
        ScoreMaxScoreHistoryProfile.INSTANCE.onBulletHit(event.getBullet().getPower(), enemyEnergyBeforeHit);
        maybeReevaluateModeSelection();
    }

    public void onHitByBullet(HitByBulletEvent event) {
        activeMode.onHitByBullet(event);
        boolean forgivenThisHit = activeModeId == ModeId.BULLET_SHIELD && remainingBulletShieldForgivenHits > 0;
        if (forgivenThisHit) {
            remainingBulletShieldForgivenHits--;
        }
        double ourEnergyBeforeHit = info.getTrackedOurEnergy();
        roundScoreTracker.onHitByBullet(event.getPower(), ourEnergyBeforeHit, forgivenThisHit);
        ScoreMaxScoreHistoryProfile.INSTANCE.onHitByBullet(event.getPower(), ourEnergyBeforeHit);
        maybeReevaluateModeSelection();
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
        maybeReevaluateModeSelection();
    }

    public void onScannedRobot(ScannedRobotEvent event) {
        if (pendingOpponentContextResolution) {
            resolvePendingOpponentContext(event);
        } else if (!roundSelectionResolved) {
            resolveRoundMode(event);
        }
        activeMode.onScannedRobot(event);
    }

    public void onRobotDeath(RobotDeathEvent event) {
        activeMode.onRobotDeath(event);
    }

    public void onRoundEnded() {
        if (info == null) {
            throw new IllegalStateException("Mode controller requires initialized info to end a round");
        }
        roundScoreTracker.onRoundEnded(info.getOurScore(), info.getOpponentScore());
        roundSelectionResolved = false;
    }

    public void onWin() {
        if (info == null) {
            throw new IllegalStateException("Mode controller requires initialized info to handle a win");
        }
        roundScoreTracker.onRoundResult(info.getOurScore(), info.getOpponentScore());
        ScoreMaxScoreHistoryProfile.INSTANCE.onWin();
    }

    public void onDeath() {
        if (info == null) {
            throw new IllegalStateException("Mode controller requires initialized info to handle a death");
        }
        roundScoreTracker.onRoundResult(info.getOurScore(), info.getOpponentScore());
        ScoreMaxScoreHistoryProfile.INSTANCE.onDeath();
    }

    private void setActiveMode(ModeId nextModeId) {
        BattleMode nextMode = modeFor(nextModeId);
        activeModeId = nextModeId;
        activeMode = nextMode;
        ScoreMaxScoreHistoryProfile.INSTANCE.setTrackingEnabled(nextModeId == ModeId.SCORE_MAX);
        if (info != null) {
            info.setScoreMaxTrackingEnabled(nextModeId == ModeId.SCORE_MAX);
        }
        observationProfile.setPolicy(nextMode.getObservationPolicy());
        modesUsedThisBattle.add(nextMode);
    }

    private double currentEnemyEnergyBeforeDamage() {
        if (info == null || info.getEnemy() == null) {
            return Double.NaN;
        }
        return info.getEnemy().energy;
    }

    private void startRoundOutcomeProfile(RoundOutcomeProfile profile, RoundOutcomeProfile otherProfile) {
        if (profile == null || profile == otherProfile) {
            return;
        }
        profile.startBattle();
    }

    private ModeId choosePreScanRoundMode() {
        if (!opponentContextLoaded) {
            return ModeId.BULLET_SHIELD;
        }
        return chooseRoundStartMode();
    }

    private void resolvePendingOpponentContext(ScannedRobotEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Mode controller requires a non-null scan event to resolve opponent context");
        }
        if (info == null || info.getRobot() == null) {
            throw new IllegalStateException("Mode controller requires initialized robot info before resolving opponent context");
        }
        dataStore.ensureOpponentDataLoaded(info.getRobot(), event.getName());
        if (!opponentContextLoaded) {
            remainingBulletShieldForgivenHits =
                    dataStore.isFirstBattleForTrackedOpponent() && info.getRobot().getRoundNum() == 0 ? 2 : 0;
        }
        opponentContextLoaded = true;
        pendingOpponentContextResolution = false;
        activateModeForRound(chooseRoundStartMode());
        battleOpeningModeResolved = true;
    }

    private void resolveRoundMode(ScannedRobotEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Mode controller requires a non-null scan event to resolve mode");
        }
        if (info == null || info.getRobot() == null) {
            throw new IllegalStateException("Mode controller requires initialized robot info before resolving mode");
        }
        dataStore.ensureOpponentDataLoaded(info.getRobot(), event.getName());
        if (!opponentContextLoaded) {
            remainingBulletShieldForgivenHits =
                    dataStore.isFirstBattleForTrackedOpponent() && info.getRobot().getRoundNum() == 0 ? 2 : 0;
            opponentContextLoaded = true;
        }
        ModeId selectedMode = chooseRoundStartMode();
        roundSelectionResolved = true;
        activateModeForRound(selectedMode);
        battleOpeningModeResolved = true;
    }

    private void activateModeForRound(ModeId selectedMode) {
        ModeId previousModeId = activeModeId;
        setActiveMode(selectedMode);
        roundScoreTracker.activateMode(selectedMode);
        if (opponentContextLoaded && !pendingOpponentContextResolution && selectedMode != ModeId.BULLET_SHIELD) {
            bulletShieldRetiredForBattle = true;
        }
        info.activateRoundOutcomeProfile(activeMode.getRoundOutcomeProfile());
        announceModeChange(previousModeId, selectedMode);
    }

    private ModeId chooseRoundStartMode() {
        if (!battleOpeningModeResolved) {
            return chooseOpeningMode();
        }
        return chooseModeForSwitch();
    }

    private ModeId chooseOpeningMode() {
        return selectOpeningMode(admissibleModePosteriors());
    }

    private void maybeReevaluateModeSelection() {
        if (!roundSelectionResolved || pendingOpponentContextResolution || !opponentContextLoaded) {
            return;
        }
        ModeId selectedMode = chooseModeForSwitch();
        if (selectedMode == activeModeId) {
            return;
        }
        activateModeForRound(selectedMode);
    }

    private ModePosterior[] admissibleModePosteriors() {
        List<ModePosterior> posteriors = new ArrayList<>();
        posteriors.add(estimateLiveMode(ModeId.SCORE_MAX));
        if (!bulletShieldRetiredForBattle) {
            posteriors.add(estimateLiveMode(ModeId.BULLET_SHIELD));
        }
        if (isPerfectPredictionAdmissible()) {
            posteriors.add(estimateLiveMode(ModeId.PERFECT_PREDICTION));
        }
        return posteriors.toArray(new ModePosterior[0]);
    }

    private ModePosterior estimateLiveMode(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Live mode estimate requires a non-null mode id");
        }
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getCombinedStats(modeId);
        double totalOurScore = stats.totalOurScore + roundScoreTracker.getLiveOurScore(modeId);
        double totalOpponentScore = stats.totalOpponentScore + roundScoreTracker.getLiveOpponentScore(modeId);
        return estimateMode(modeId, totalOurScore, totalOpponentScore);
    }

    private ModeId chooseModeForSwitch() {
        ModePosterior[] posteriors = admissibleModePosteriors();
        ModePosterior current = findPosterior(posteriors, activeModeId);
        if (current == null) {
            return selectOpeningMode(posteriors);
        }
        double bestOtherReferenceMean = bestOtherSwitchReferenceMean(posteriors, activeModeId);
        if (current.upperBound >= bestOtherReferenceMean) {
            return activeModeId;
        }
        ModePosterior[] alternatives = excludeMode(posteriors, activeModeId);
        if (alternatives.length == 0) {
            return activeModeId;
        }
        return selectOpeningMode(alternatives);
    }

    private static ModeId selectOpeningMode(ModePosterior[] posteriors) {
        if (posteriors == null || posteriors.length < 1) {
            throw new IllegalArgumentException("Mode selection requires at least one admissible mode");
        }
        if (posteriors.length == 1) {
            return posteriors[0].modeId;
        }

        ModePosterior fallback = posteriors[0];
        for (ModePosterior posterior : posteriors) {
            if (prefersHigherMeanFallback(posterior, fallback)) {
                fallback = posterior;
            }
        }

        ModePosterior[] ordered = posteriors.clone();
        for (int i = 0; i < ordered.length - 1; i++) {
            int bestIndex = i;
            for (int j = i + 1; j < ordered.length; j++) {
                if (prefersOpeningOrder(ordered[j], ordered[bestIndex])) {
                    bestIndex = j;
                }
            }
            if (bestIndex != i) {
                ModePosterior swap = ordered[i];
                ordered[i] = ordered[bestIndex];
                ordered[bestIndex] = swap;
            }
        }

        for (ModePosterior candidate : ordered) {
            double bestOtherMean = Double.NEGATIVE_INFINITY;
            for (ModePosterior other : posteriors) {
                if (other.modeId == candidate.modeId) {
                    continue;
                }
                if (other.posteriorMean > bestOtherMean) {
                    bestOtherMean = other.posteriorMean;
                }
            }
            if (candidate.upperBound > bestOtherMean
                    || (candidate.modeId == ModeId.BULLET_SHIELD && nearlyEqual(candidate.upperBound, bestOtherMean))) {
                return candidate.modeId;
            }
        }

        return fallback.modeId;
    }

    private static ModePosterior estimateMode(ModeId modeId, ModePerformanceProfile.ModeStatsSnapshot stats) {
        if (modeId == null || stats == null) {
            throw new IllegalArgumentException("Mode estimate requires non-null mode stats");
        }
        return estimateMode(modeId, stats.totalOurScore, stats.totalOpponentScore);
    }

    private static ModePosterior estimateMode(ModeId modeId, double totalOurScore, double totalOpponentScore) {
        if (modeId == null) {
            throw new IllegalArgumentException("Mode estimate requires a non-null mode id");
        }
        if (!(totalOurScore >= 0.0) || !(totalOpponentScore >= 0.0)) {
            throw new IllegalArgumentException("Mode estimate totals must be non-negative");
        }
        double totalScore = totalOurScore + totalOpponentScore;
        double priorScale = Math.max(0.0, 1.0 - totalScore / MODE_PRIOR_FADE_SCORE);
        double priorOurScore = MODE_PRIOR_SCORE * priorScale;
        double effectiveTotalScore = totalScore + priorOurScore;
        double posteriorMean = effectiveTotalScore > 0.0
                ? (totalOurScore + priorOurScore) / effectiveTotalScore
                : 1.0;
        double uncertaintyAlpha =
                MODE_UNCERTAINTY_PRIOR_EPSILON
                        + MODE_UNCERTAINTY_PRIOR_ALPHA * priorScale
                        + totalOurScore / POSTERIOR_SCORE_UNIT;
        double uncertaintyBeta =
                MODE_UNCERTAINTY_PRIOR_EPSILON
                        + MODE_UNCERTAINTY_PRIOR_BETA * priorScale
                        + totalOpponentScore / POSTERIOR_SCORE_UNIT;
        double variance = (uncertaintyAlpha * uncertaintyBeta)
                / ((uncertaintyAlpha + uncertaintyBeta)
                * (uncertaintyAlpha + uncertaintyBeta)
                * (uncertaintyAlpha + uncertaintyBeta + 1.0));
        double uncertainty = Math.sqrt(Math.max(0.0, variance));
        double lowerBound = clampUnitInterval(posteriorMean - MODE_SELECTION_CONFIDENCE_SCALE * uncertainty);
        double upperBound = clampUnitInterval(posteriorMean + MODE_SELECTION_CONFIDENCE_SCALE * uncertainty);
        boolean hasEvidence = totalScore > 0.0;
        return new ModePosterior(modeId, posteriorMean, uncertainty, lowerBound, upperBound, hasEvidence);
    }

    private static double clampUnitInterval(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean prefersHigherMeanFallback(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.posteriorMean > incumbent.posteriorMean) {
            return true;
        }
        if (candidate.posteriorMean < incumbent.posteriorMean) {
            return false;
        }
        return prefersBulletShieldTie(candidate.modeId, incumbent.modeId);
    }

    private static boolean prefersOpeningOrder(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.uncertainty > incumbent.uncertainty) {
            return true;
        }
        if (candidate.uncertainty < incumbent.uncertainty) {
            return false;
        }
        if (candidate.upperBound > incumbent.upperBound) {
            return true;
        }
        if (candidate.upperBound < incumbent.upperBound) {
            return false;
        }
        if (candidate.posteriorMean > incumbent.posteriorMean) {
            return true;
        }
        if (candidate.posteriorMean < incumbent.posteriorMean) {
            return false;
        }
        return prefersBulletShieldTie(candidate.modeId, incumbent.modeId);
    }

    private static boolean prefersBulletShieldTie(ModeId candidate, ModeId incumbent) {
        return candidate == ModeId.BULLET_SHIELD && incumbent != ModeId.BULLET_SHIELD;
    }

    private static boolean nearlyEqual(double first, double second) {
        return Math.abs(first - second) <= 1e-9;
    }

    private static ModePosterior findPosterior(ModePosterior[] posteriors, ModeId modeId) {
        if (posteriors == null || modeId == null) {
            return null;
        }
        for (ModePosterior posterior : posteriors) {
            if (posterior.modeId == modeId) {
                return posterior;
            }
        }
        return null;
    }

    private static double bestOtherSwitchReferenceMean(ModePosterior[] posteriors, ModeId currentModeId) {
        double bestOtherMean = Double.NEGATIVE_INFINITY;
        for (ModePosterior other : posteriors) {
            if (other.modeId == currentModeId) {
                continue;
            }
            double referenceMean = other.hasEvidence ? other.posteriorMean : 1.0;
            if (referenceMean > bestOtherMean) {
                bestOtherMean = referenceMean;
            }
        }
        return bestOtherMean;
    }

    private static ModePosterior[] excludeMode(ModePosterior[] posteriors, ModeId modeId) {
        List<ModePosterior> filtered = new ArrayList<>();
        for (ModePosterior posterior : posteriors) {
            if (posterior.modeId != modeId) {
                filtered.add(posterior);
            }
        }
        return filtered.toArray(new ModePosterior[0]);
    }

    private boolean isPerfectPredictionAdmissible() {
        if (info == null) {
            return false;
        }
        if (ModePerformanceProfile.hasAnyCombinedSamples(ModeId.PERFECT_PREDICTION)) {
            return true;
        }
        if (!info.isPerfectPredictionUnlocked()) {
            return false;
        }
        return !areLegalModesSettled();
    }

    private boolean areLegalModesSettled() {
        List<ModePosterior> legal = new ArrayList<>();
        legal.add(estimateLiveMode(ModeId.SCORE_MAX));
        if (!bulletShieldRetiredForBattle) {
            legal.add(estimateLiveMode(ModeId.BULLET_SHIELD));
        }
        ModePosterior best = null;
        for (ModePosterior p : legal) {
            if (best == null || p.posteriorMean > best.posteriorMean) {
                best = p;
            }
        }
        if (best.upperBound - best.lowerBound >= MODE_SETTLED_CI_WIDTH) {
            return false;
        }
        for (ModePosterior p : legal) {
            if (p.modeId != best.modeId && p.upperBound > best.posteriorMean) {
                return false;
            }
        }
        return true;
    }

    private BattleMode modeFor(ModeId modeId) {
        if (modeId == ModeId.SCORE_MAX) {
            return scoreMaxMode;
        }
        if (modeId == ModeId.BULLET_SHIELD) {
            return bulletShieldMode;
        }
        if (modeId == ModeId.PERFECT_PREDICTION) {
            return perfectPredictionMode;
        }
        throw new IllegalArgumentException("Unsupported mode id " + modeId);
    }

    private void announceModeChange(ModeId fromModeId, ModeId toModeId) {
        if (!battleModeAnnouncementPrinted) {
            announceBattleMode(toModeId);
            battleModeAnnouncementPrinted = true;
            return;
        }
        announceModeSwitch(fromModeId, toModeId);
    }

    private void announceBattleMode(ModeId modeId) {
        Saguaro robot = currentRobot();
        if (robot == null) {
            return;
        }
        robot.out.println("Battle mode: " + modeDisplayName(modeId));
    }

    private void announceModeSwitch(ModeId fromModeId, ModeId toModeId) {
        if (fromModeId == null || toModeId == null || fromModeId == toModeId) {
            return;
        }
        Saguaro robot = currentRobot();
        if (robot == null) {
            return;
        }
        robot.out.println("Mode switch: " + modeDisplayName(fromModeId) + " -> " + modeDisplayName(toModeId));
    }

    private Saguaro currentRobot() {
        return info != null ? info.getRobot() : null;
    }

    private static String modeDisplayName(ModeId modeId) {
        if (modeId == ModeId.SCORE_MAX) {
            return "ScoreMax";
        }
        if (modeId == ModeId.BULLET_SHIELD) {
            return "BulletShielding";
        }
        if (modeId == ModeId.PERFECT_PREDICTION) {
            return "PerfectPrediction";
        }
        throw new IllegalArgumentException("Unsupported mode id " + modeId);
    }

    private static final class ModePosterior {
        final ModeId modeId;
        final double posteriorMean;
        final double uncertainty;
        final double lowerBound;
        final double upperBound;
        final boolean hasEvidence;

        ModePosterior(ModeId modeId,
                      double posteriorMean,
                      double uncertainty,
                      double lowerBound,
                      double upperBound,
                      boolean hasEvidence) {
            this.modeId = modeId;
            this.posteriorMean = posteriorMean;
            this.uncertainty = uncertainty;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.hasEvidence = hasEvidence;
        }
    }

    private static final class ModeRoundScoreTracker {
        private Segment currentSegment;
        private double trackedBattleOurScore;
        private double trackedBattleOpponentScore;
        private double ourCreditedDamageOnEnemyThisRound;
        private double enemyCreditedDamageOnUsThisRound;
        private boolean ourKillBonusAppliedThisRound;
        private boolean enemyKillBonusAppliedThisRound;
        private boolean roundEndedObserved;
        private boolean roundResultObserved;
        private double pendingRoundOurScore;
        private double pendingRoundOpponentScore;

        void startBattle() {
            currentSegment = null;
            trackedBattleOurScore = 0.0;
            trackedBattleOpponentScore = 0.0;
            clearRoundDamageState();
            clearPendingRoundState();
        }

        void startRound() {
            finalizePendingRoundIfNeeded();
            currentSegment = null;
            trackedBattleOurScore = 0.0;
            trackedBattleOpponentScore = 0.0;
            clearRoundDamageState();
            clearPendingRoundState();
        }

        void activateMode(ModeId modeId) {
            if (modeId == null) {
                throw new IllegalArgumentException("Round score tracker requires a non-null mode id");
            }
            if (currentSegment != null && currentSegment.modeId == modeId) {
                return;
            }
            finalizeCurrentSegment();
            currentSegment = new Segment(modeId);
        }

        void onBulletHit(double bulletPower, double enemyEnergyBeforeHit) {
            if (currentSegment == null) {
                return;
            }
            double creditedDamage = RobocodeScoreUtil.creditedBulletDamage(bulletPower, enemyEnergyBeforeHit);
            currentSegment.ourScore += creditedDamage;
            trackedBattleOurScore += creditedDamage;
            ourCreditedDamageOnEnemyThisRound += creditedDamage;
            if (!ourKillBonusAppliedThisRound
                    && RobocodeScoreUtil.isLethalDamage(enemyEnergyBeforeHit, creditedDamage)) {
                double bonus = RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * ourCreditedDamageOnEnemyThisRound;
                currentSegment.ourScore += bonus;
                trackedBattleOurScore += bonus;
                ourKillBonusAppliedThisRound = true;
            }
        }

        void onHitByBullet(double bulletPower, double ourEnergyBeforeHit, boolean forgivenThisHit) {
            if (currentSegment == null) {
                return;
            }
            double score = RobocodeScoreUtil.creditedBulletDamage(bulletPower, ourEnergyBeforeHit);
            Segment segment = currentSegment;
            segment.opponentScore += score;
            trackedBattleOpponentScore += score;
            enemyCreditedDamageOnUsThisRound += score;
            if (forgivenThisHit) {
                segment.forgivenOpponentScore += score;
            }
            if (!enemyKillBonusAppliedThisRound
                    && RobocodeScoreUtil.isLethalDamage(ourEnergyBeforeHit, score)) {
                double bonus = RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * enemyCreditedDamageOnUsThisRound;
                segment.opponentScore += bonus;
                trackedBattleOpponentScore += bonus;
                enemyKillBonusAppliedThisRound = true;
            }
        }

        void onHitRobot(boolean myFault, double enemyEnergyBeforeCollision, double ourEnergyBeforeCollision) {
            if (currentSegment == null) {
                return;
            }
            Segment segment = currentSegment;
            if (myFault) {
                double creditedDamage = RobocodeScoreUtil.creditedRamDamage(enemyEnergyBeforeCollision);
                double score = RobocodeScoreUtil.ramDamageScore(creditedDamage);
                segment.ourScore += score;
                trackedBattleOurScore += score;
                ourCreditedDamageOnEnemyThisRound += creditedDamage;
                if (!ourKillBonusAppliedThisRound
                        && RobocodeScoreUtil.isLethalDamage(enemyEnergyBeforeCollision, creditedDamage)) {
                    double bonus = RobocodeScoreUtil.RAM_KILL_BONUS_MULTIPLIER * ourCreditedDamageOnEnemyThisRound;
                    segment.ourScore += bonus;
                    trackedBattleOurScore += bonus;
                    ourKillBonusAppliedThisRound = true;
                }
                return;
            }

            double creditedDamage = RobocodeScoreUtil.creditedRamDamage(ourEnergyBeforeCollision);
            double score = RobocodeScoreUtil.ramDamageScore(creditedDamage);
            segment.opponentScore += score;
            trackedBattleOpponentScore += score;
            enemyCreditedDamageOnUsThisRound += creditedDamage;
            if (!enemyKillBonusAppliedThisRound
                    && RobocodeScoreUtil.isLethalDamage(ourEnergyBeforeCollision, creditedDamage)) {
                double bonus = RobocodeScoreUtil.RAM_KILL_BONUS_MULTIPLIER * enemyCreditedDamageOnUsThisRound;
                segment.opponentScore += bonus;
                trackedBattleOpponentScore += bonus;
                enemyKillBonusAppliedThisRound = true;
            }
        }

        void onRoundEnded(double actualOurScore, double actualOpponentScore) {
            roundEndedObserved = true;
            pendingRoundOurScore = actualOurScore;
            pendingRoundOpponentScore = actualOpponentScore;
            if (roundResultObserved) {
                finalizeRound(actualOurScore, actualOpponentScore);
            }
        }

        void onRoundResult(double actualOurScore, double actualOpponentScore) {
            roundResultObserved = true;
            if (roundEndedObserved) {
                finalizeRound(actualOurScore, actualOpponentScore);
            }
        }

        double getLiveOurScore(ModeId modeId) {
            if (modeId == null) {
                throw new IllegalArgumentException("Live round score lookup requires a non-null mode id");
            }
            if (currentSegment == null || currentSegment.modeId != modeId) {
                return 0.0;
            }
            return currentSegment.ourScore;
        }

        double getLiveOpponentScore(ModeId modeId) {
            if (modeId == null) {
                throw new IllegalArgumentException("Live round score lookup requires a non-null mode id");
            }
            if (currentSegment == null || currentSegment.modeId != modeId) {
                return 0.0;
            }
            return currentSegment.opponentScore;
        }

        private void finalizePendingRoundIfNeeded() {
            if (!roundEndedObserved) {
                return;
            }
            finalizeRound(pendingRoundOurScore, pendingRoundOpponentScore);
        }

        private void finalizeRound(double actualOurScore, double actualOpponentScore) {
            double ourResidual = actualOurScore - trackedBattleOurScore;
            double opponentResidual = actualOpponentScore - trackedBattleOpponentScore;
            if (ourResidual < -1e-6 || opponentResidual < -1e-6) {
                System.out.println(String.format(
                        Locale.US,
                        "[WARN] Mode score tracker adjusted negative round residual: actual=(%.3f, %.3f) tracked=(%.3f, %.3f)",
                        actualOurScore,
                        actualOpponentScore,
                        trackedBattleOurScore,
                        trackedBattleOpponentScore));
            }
            if (currentSegment != null) {
                currentSegment.ourScore = Math.max(0.0, currentSegment.ourScore + ourResidual);
                currentSegment.opponentScore = Math.max(0.0, currentSegment.opponentScore + opponentResidual);
                currentSegment.forgivenOpponentScore =
                        Math.max(0.0, Math.min(currentSegment.forgivenOpponentScore, currentSegment.opponentScore));
                trackedBattleOurScore = actualOurScore;
                trackedBattleOpponentScore = actualOpponentScore;
            }
            finalizeCurrentSegment();
            clearPendingRoundState();
        }

        private void clearRoundDamageState() {
            ourCreditedDamageOnEnemyThisRound = 0.0;
            enemyCreditedDamageOnUsThisRound = 0.0;
            ourKillBonusAppliedThisRound = false;
            enemyKillBonusAppliedThisRound = false;
        }

        private void clearPendingRoundState() {
            roundEndedObserved = false;
            roundResultObserved = false;
            pendingRoundOurScore = 0.0;
            pendingRoundOpponentScore = 0.0;
        }

        private void finalizeCurrentSegment() {
            if (currentSegment == null) {
                return;
            }
            ModePerformanceProfile.recordSample(
                    currentSegment.modeId,
                    currentSegment.ourScore,
                    currentSegment.opponentScore,
                    Math.max(0.0, currentSegment.opponentScore - currentSegment.forgivenOpponentScore));
            currentSegment = null;
        }

        private static final class Segment {
            final ModeId modeId;
            double ourScore;
            double opponentScore;
            double forgivenOpponentScore;

            Segment(ModeId modeId) {
                this.modeId = modeId;
            }
        }
    }
}
