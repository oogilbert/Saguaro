package oog.mega.saguaro.info.learning;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.mode.ModePerformanceProfile;
import robocode.Rules;

public final class ScoreMaxScoreHistoryProfile implements RoundOutcomeProfile {
    public static final ScoreMaxScoreHistoryProfile INSTANCE = new ScoreMaxScoreHistoryProfile();
    private final RobocodeScoreUtil.HitScoreScratch hitScoreScratch = new RobocodeScoreUtil.HitScoreScratch();

    private static double currentBattleOurScore;
    private static double currentBattleOpponentScore;
    private static double ourCreditedDamageOnEnemyThisRound;
    private static double enemyCreditedDamageOnUsThisRound;
    private static boolean trackingEnabled;
    private static boolean roundResultApplied;
    private static boolean ourKillBonusAppliedThisRound;
    private static boolean enemyKillBonusAppliedThisRound;

    private ScoreMaxScoreHistoryProfile() {
    }

    @Override
    public void startBattle() {
        currentBattleOurScore = 0.0;
        currentBattleOpponentScore = 0.0;
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
        RobocodeScoreUtil.scoreBulletHit(
                bulletPower,
                enemyEnergyBeforeHit,
                ourCreditedDamageOnEnemyThisRound,
                ourKillBonusAppliedThisRound,
                hitScoreScratch);
        currentBattleOurScore += hitScoreScratch.scoreDelta;
        ourCreditedDamageOnEnemyThisRound = hitScoreScratch.cumulativeCreditedDamage;
        ourKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    public void onHitByBullet(double bulletPower, double ourEnergyBeforeHit) {
        if (!trackingEnabled) {
            return;
        }
        RobocodeScoreUtil.scoreBulletHit(
                bulletPower,
                ourEnergyBeforeHit,
                enemyCreditedDamageOnUsThisRound,
                enemyKillBonusAppliedThisRound,
                hitScoreScratch);
        currentBattleOpponentScore += hitScoreScratch.scoreDelta;
        enemyCreditedDamageOnUsThisRound = hitScoreScratch.cumulativeCreditedDamage;
        enemyKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    public void onHitRobot(boolean myFault,
                           double enemyEnergyBeforeCollision,
                           double ourEnergyBeforeCollision) {
        if (!trackingEnabled) {
            return;
        }
        if (myFault) {
            RobocodeScoreUtil.scoreRamHit(
                    enemyEnergyBeforeCollision,
                    ourCreditedDamageOnEnemyThisRound,
                    ourKillBonusAppliedThisRound,
                    hitScoreScratch);
            currentBattleOurScore += hitScoreScratch.scoreDelta;
            ourCreditedDamageOnEnemyThisRound = hitScoreScratch.cumulativeCreditedDamage;
            ourKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
            return;
        }

        RobocodeScoreUtil.scoreRamHit(
                ourEnergyBeforeCollision,
                enemyCreditedDamageOnUsThisRound,
                enemyKillBonusAppliedThisRound,
                hitScoreScratch);
        currentBattleOpponentScore += hitScoreScratch.scoreDelta;
        enemyCreditedDamageOnUsThisRound = hitScoreScratch.cumulativeCreditedDamage;
        enemyKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
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
        return historicalScoreShare();
    }

    public double getCurrentBattleOurScore() {
        return currentBattleOurScore;
    }

    public double getCurrentBattleOpponentScore() {
        return currentBattleOpponentScore;
    }

    private double historicalScoreShare() {
        ModePerformanceProfile.ModeStatsSnapshot scoreMaxStats = ModePerformanceProfile.getPersistedStats(ModeId.SCORE_MAX);
        double scoreMaxDenominator = scoreMaxStats.totalOurScore + scoreMaxStats.totalOpponentScore;
        if (scoreMaxDenominator > 0.0) {
            return scoreMaxStats.totalOurScore / scoreMaxDenominator;
        }
        return BotConfig.Learning.DEFAULT_SCORE_SHARE_PRIOR;
    }
}
