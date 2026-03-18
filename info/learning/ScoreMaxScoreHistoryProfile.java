package oog.mega.saguaro.info.learning;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import robocode.Rules;

public final class ScoreMaxScoreHistoryProfile implements RoundOutcomeProfile {
    private static final double DEFAULT_SCORE_SHARE_PRIOR = 0.5;
    private static final int SECTION_VERSION = 2;
    private static final int SECTION_BYTES = 16;

    public static final ScoreMaxScoreHistoryProfile INSTANCE = new ScoreMaxScoreHistoryProfile();

    private static double persistedOurScore;
    private static double persistedOpponentScore;
    private static boolean persistedScoreHistoryLoaded;
    private static double currentBattleOurScore;
    private static double currentBattleOpponentScore;
    private static double ourCreditedDamageOnEnemyThisRound;
    private static double enemyCreditedDamageOnUsThisRound;
    private static String trackedOpponentName;
    private static boolean trackingEnabled;
    private static boolean roundResultApplied;
    private static boolean ourKillBonusAppliedThisRound;
    private static boolean enemyKillBonusAppliedThisRound;

    private ScoreMaxScoreHistoryProfile() {
    }

    @Override
    public void startBattle() {
        persistedOurScore = 0.0;
        persistedOpponentScore = 0.0;
        persistedScoreHistoryLoaded = false;
        currentBattleOurScore = 0.0;
        currentBattleOpponentScore = 0.0;
        trackedOpponentName = null;
        trackingEnabled = false;
        startRound();
    }

    public void startRound() {
        roundResultApplied = false;
        ourCreditedDamageOnEnemyThisRound = 0.0;
        enemyCreditedDamageOnUsThisRound = 0.0;
        ourKillBonusAppliedThisRound = false;
        enemyKillBonusAppliedThisRound = false;
    }

    public void setTrackingEnabled(boolean enabled) {
        trackingEnabled = enabled;
    }

    public void onBulletHit(double bulletPower, double enemyEnergyBeforeHit) {
        if (!trackingEnabled) {
            return;
        }
        double creditedDamage = RobocodeScoreUtil.creditedBulletDamage(bulletPower, enemyEnergyBeforeHit);
        currentBattleOurScore += creditedDamage;
        ourCreditedDamageOnEnemyThisRound += creditedDamage;
        if (!ourKillBonusAppliedThisRound
                && RobocodeScoreUtil.isLethalDamage(enemyEnergyBeforeHit, creditedDamage)) {
            currentBattleOurScore += RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * ourCreditedDamageOnEnemyThisRound;
            ourKillBonusAppliedThisRound = true;
        }
    }

    public void onHitByBullet(double bulletPower, double ourEnergyBeforeHit) {
        if (!trackingEnabled) {
            return;
        }
        double creditedDamage = RobocodeScoreUtil.creditedBulletDamage(bulletPower, ourEnergyBeforeHit);
        currentBattleOpponentScore += creditedDamage;
        enemyCreditedDamageOnUsThisRound += creditedDamage;
        if (!enemyKillBonusAppliedThisRound
                && RobocodeScoreUtil.isLethalDamage(ourEnergyBeforeHit, creditedDamage)) {
            currentBattleOpponentScore +=
                    RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * enemyCreditedDamageOnUsThisRound;
            enemyKillBonusAppliedThisRound = true;
        }
    }

    public void onHitRobot(boolean myFault,
                           double enemyEnergyBeforeCollision,
                           double ourEnergyBeforeCollision) {
        if (!trackingEnabled) {
            return;
        }
        if (myFault) {
            double creditedDamage = RobocodeScoreUtil.creditedRamDamage(enemyEnergyBeforeCollision);
            currentBattleOurScore += RobocodeScoreUtil.ramDamageScore(creditedDamage);
            ourCreditedDamageOnEnemyThisRound += creditedDamage;
            if (!ourKillBonusAppliedThisRound
                    && RobocodeScoreUtil.isLethalDamage(enemyEnergyBeforeCollision, creditedDamage)) {
                currentBattleOurScore +=
                        RobocodeScoreUtil.RAM_KILL_BONUS_MULTIPLIER * ourCreditedDamageOnEnemyThisRound;
                ourKillBonusAppliedThisRound = true;
            }
            return;
        }

        double creditedDamage = RobocodeScoreUtil.creditedRamDamage(ourEnergyBeforeCollision);
        currentBattleOpponentScore += RobocodeScoreUtil.ramDamageScore(creditedDamage);
        enemyCreditedDamageOnUsThisRound += creditedDamage;
        if (!enemyKillBonusAppliedThisRound
                && RobocodeScoreUtil.isLethalDamage(ourEnergyBeforeCollision, creditedDamage)) {
            currentBattleOpponentScore +=
                    RobocodeScoreUtil.RAM_KILL_BONUS_MULTIPLIER * enemyCreditedDamageOnUsThisRound;
            enemyKillBonusAppliedThisRound = true;
        }
    }

    public void onWin() {
        if (roundResultApplied) {
            return;
        }
        if (trackingEnabled) {
            currentBattleOurScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        }
        roundResultApplied = true;
    }

    public void onDeath() {
        if (roundResultApplied) {
            return;
        }
        if (trackingEnabled) {
            currentBattleOpponentScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        }
        roundResultApplied = true;
    }

    @Override
    public int recordWin() {
        return 0;
    }

    @Override
    public int recordLoss() {
        return 0;
    }

    @Override
    public double getSurvivalPrior() {
        return combinedScoreShare();
    }

    public double getCombinedOurScore() {
        return persistedOurScore + currentBattleOurScore;
    }

    public double getCombinedOpponentScore() {
        return persistedOpponentScore + currentBattleOpponentScore;
    }

    public static int persistenceSectionVersion() {
        return SECTION_VERSION;
    }

    public static void ensureOpponentBaselineLoaded(String opponentName, int sectionVersion, byte[] payload) {
        if (opponentName == null || opponentName.isEmpty()) {
            throw new IllegalArgumentException("Score history profile requires a non-empty opponent name");
        }
        if (trackedOpponentName == null) {
            trackedOpponentName = opponentName;
            clearPersistedState();
            if (payload != null) {
                loadPersistedPayload(sectionVersion, payload);
            }
            return;
        }
        if (!trackedOpponentName.equals(opponentName)) {
            throw new IllegalStateException(
                    "Score history profile expected a single opponent but saw: "
                            + trackedOpponentName + " and " + opponentName);
        }
    }

    public static boolean hasPersistedSectionData() {
        return trackedOpponentName != null
                && (persistedOurScore > 0.0
                || persistedOpponentScore > 0.0
                || currentBattleOurScore > 0.0
                || currentBattleOpponentScore > 0.0);
    }

    public static boolean isPersistedScoreHistoryLoaded() {
        return persistedScoreHistoryLoaded;
    }

    public static byte[] createPersistedSectionPayload(int maxPayloadBytes, boolean includeCurrentBattleData) {
        if (maxPayloadBytes < SECTION_BYTES) {
            throw new IllegalStateException(
                    "Insufficient data quota to save score-history payload: payload budget=" + maxPayloadBytes + " bytes");
        }
        double ourScoreToPersist = includeCurrentBattleData ? currentBattleOurScore : persistedOurScore;
        double opponentScoreToPersist = includeCurrentBattleData ? currentBattleOpponentScore : persistedOpponentScore;
        if (!(ourScoreToPersist > 0.0 || opponentScoreToPersist > 0.0)) {
            return null;
        }

        try (ByteArrayOutputStream raw = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(raw)) {
            out.writeDouble(ourScoreToPersist);
            out.writeDouble(opponentScoreToPersist);
            out.flush();
            byte[] payload = raw.toByteArray();
            if (payload.length > maxPayloadBytes) {
                throw new IllegalStateException(
                        "Insufficient data quota to save score-history payload: required=" + payload.length
                                + " bytes, available=" + maxPayloadBytes + " bytes");
            }
            return payload;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build score-history payload", e);
        }
    }

    private static void clearPersistedState() {
        persistedOurScore = 0.0;
        persistedOpponentScore = 0.0;
        persistedScoreHistoryLoaded = false;
    }

    private static void loadPersistedPayload(int sectionVersion, byte[] payload) {
        if (sectionVersion != SECTION_VERSION) {
            throw new IllegalStateException("Unsupported score-history section version " + sectionVersion);
        }
        if (payload.length != SECTION_BYTES) {
            throw new IllegalStateException("Unexpected score-history payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            double loadedOurScore = in.readDouble();
            double loadedOpponentScore = in.readDouble();
            validateLoadedTotals(loadedOurScore, loadedOpponentScore);
            if (in.available() != 0) {
                throw new IllegalStateException("Score-history payload contained trailing bytes");
            }
            persistedOurScore = loadedOurScore;
            persistedOpponentScore = loadedOpponentScore;
            persistedScoreHistoryLoaded = true;
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable score-history payload", e);
        }
    }

    private double combinedScoreShare() {
        double denominator = getCombinedOurScore() + getCombinedOpponentScore();
        return denominator > 0.0 ? getCombinedOurScore() / denominator : DEFAULT_SCORE_SHARE_PRIOR;
    }

    private static void validateLoadedTotals(double totalOurScore, double totalOpponentScore) {
        if (!Double.isFinite(totalOurScore) || !Double.isFinite(totalOpponentScore)) {
            throw new IllegalStateException("Score-history payload must contain finite values");
        }
        if (totalOurScore < 0.0 || totalOpponentScore < 0.0) {
            throw new IllegalStateException("Score-history payload must contain non-negative values");
        }
    }
}
