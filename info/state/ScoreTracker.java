package oog.mega.saguaro.info.state;

import oog.mega.saguaro.info.learning.RoundOutcomeProfile;

public final class ScoreTracker {
    private final RobocodeScoreUtil.HitScoreScratch hitScoreScratch = new RobocodeScoreUtil.HitScoreScratch();
    private RoundOutcomeProfile roundOutcomeProfile;
    private boolean roundResultApplied;
    private double ourScore;
    private double opponentScore;
    private double ourBulletDamageOnEnemyThisRound;
    private double enemyBulletDamageOnUsThisRound;
    private double ourRammingDamageOnEnemyThisRound;
    private double enemyRammingDamageOnUsThisRound;
    private double ourCreditedDamageOnEnemyThisRound;
    private double enemyCreditedDamageOnUsThisRound;
    private boolean ourKillBonusAppliedThisRound;
    private boolean enemyKillBonusAppliedThisRound;

    public void init(RoundOutcomeProfile roundOutcomeProfile) {
        if (roundOutcomeProfile == null) {
            throw new IllegalArgumentException("ScoreTracker requires non-null round outcome profile");
        }
        this.roundOutcomeProfile = roundOutcomeProfile;
        roundResultApplied = false;
        ourScore = 0.0;
        opponentScore = 0.0;
        ourBulletDamageOnEnemyThisRound = 0.0;
        enemyBulletDamageOnUsThisRound = 0.0;
        ourRammingDamageOnEnemyThisRound = 0.0;
        enemyRammingDamageOnUsThisRound = 0.0;
        ourCreditedDamageOnEnemyThisRound = 0.0;
        enemyCreditedDamageOnUsThisRound = 0.0;
        ourKillBonusAppliedThisRound = false;
        enemyKillBonusAppliedThisRound = false;
    }

    public void setRoundOutcomeProfile(RoundOutcomeProfile roundOutcomeProfile) {
        if (roundOutcomeProfile == null) {
            throw new IllegalArgumentException("Round outcome profile must be non-null");
        }
        this.roundOutcomeProfile = roundOutcomeProfile;
    }

    public void onBulletHit(double bulletPower, double enemyEnergyBeforeHit) {
        RobocodeScoreUtil.scoreBulletHit(
                bulletPower,
                enemyEnergyBeforeHit,
                ourCreditedDamageOnEnemyThisRound,
                ourKillBonusAppliedThisRound,
                hitScoreScratch);
        ourScore += hitScoreScratch.scoreDelta;
        ourBulletDamageOnEnemyThisRound += hitScoreScratch.creditedDamage;
        ourCreditedDamageOnEnemyThisRound = hitScoreScratch.cumulativeCreditedDamage;
        ourKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    public void onHitByBullet(double bulletPower, double ourEnergyBeforeHit) {
        RobocodeScoreUtil.scoreBulletHit(
                bulletPower,
                ourEnergyBeforeHit,
                enemyCreditedDamageOnUsThisRound,
                enemyKillBonusAppliedThisRound,
                hitScoreScratch);
        opponentScore += hitScoreScratch.scoreDelta;
        enemyBulletDamageOnUsThisRound += hitScoreScratch.creditedDamage;
        enemyCreditedDamageOnUsThisRound = hitScoreScratch.cumulativeCreditedDamage;
        enemyKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    public void onHitRobot(boolean myFault,
                           double enemyEnergyBeforeCollision,
                           double ourEnergyBeforeCollision) {
        if (myFault) {
            RobocodeScoreUtil.scoreRamHit(
                    enemyEnergyBeforeCollision,
                    ourCreditedDamageOnEnemyThisRound,
                    ourKillBonusAppliedThisRound,
                    hitScoreScratch);
            ourScore += hitScoreScratch.scoreDelta;
            ourRammingDamageOnEnemyThisRound += hitScoreScratch.creditedDamage;
            ourCreditedDamageOnEnemyThisRound = hitScoreScratch.cumulativeCreditedDamage;
            ourKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
            return;
        }

        RobocodeScoreUtil.scoreRamHit(
                ourEnergyBeforeCollision,
                enemyCreditedDamageOnUsThisRound,
                enemyKillBonusAppliedThisRound,
                hitScoreScratch);
        opponentScore += hitScoreScratch.scoreDelta;
        enemyRammingDamageOnUsThisRound += hitScoreScratch.creditedDamage;
        enemyCreditedDamageOnUsThisRound = hitScoreScratch.cumulativeCreditedDamage;
        enemyKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    public void onWin() {
        if (roundResultApplied) {
            return;
        }
        ourScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        roundOutcomeProfile.recordWin();
        roundResultApplied = true;
    }

    public void onDeath() {
        if (roundResultApplied) {
            return;
        }
        opponentScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        roundOutcomeProfile.recordLoss();
        roundResultApplied = true;
    }

    public double getOurBulletDamageOnEnemyThisRound() {
        return ourBulletDamageOnEnemyThisRound;
    }

    public double getEnemyBulletDamageOnUsThisRound() {
        return enemyBulletDamageOnUsThisRound;
    }

    public double getOurRammingDamageOnEnemyThisRound() {
        return ourRammingDamageOnEnemyThisRound;
    }

    public double getEnemyRammingDamageOnUsThisRound() {
        return enemyRammingDamageOnUsThisRound;
    }

    public double getOurCreditedDamageOnEnemyThisRound() {
        return ourCreditedDamageOnEnemyThisRound;
    }

    public double getEnemyCreditedDamageOnUsThisRound() {
        return enemyCreditedDamageOnUsThisRound;
    }

    public double getOurScore() {
        return ourScore;
    }

    public double getOpponentScore() {
        return opponentScore;
    }
}
