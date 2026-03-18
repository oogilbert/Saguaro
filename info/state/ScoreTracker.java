package oog.mega.saguaro.info.state;

import oog.mega.saguaro.info.learning.RoundOutcomeProfile;

public final class ScoreTracker {
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
        double creditedDamage = RobocodeScoreUtil.creditedBulletDamage(bulletPower, enemyEnergyBeforeHit);
        ourScore += creditedDamage;
        ourBulletDamageOnEnemyThisRound += creditedDamage;
        ourCreditedDamageOnEnemyThisRound += creditedDamage;
        if (!ourKillBonusAppliedThisRound
                && RobocodeScoreUtil.isLethalDamage(enemyEnergyBeforeHit, creditedDamage)) {
            ourScore += RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * ourCreditedDamageOnEnemyThisRound;
            ourKillBonusAppliedThisRound = true;
        }
    }

    public void onHitByBullet(double bulletPower, double ourEnergyBeforeHit) {
        double creditedDamage = RobocodeScoreUtil.creditedBulletDamage(bulletPower, ourEnergyBeforeHit);
        opponentScore += creditedDamage;
        enemyBulletDamageOnUsThisRound += creditedDamage;
        enemyCreditedDamageOnUsThisRound += creditedDamage;
        if (!enemyKillBonusAppliedThisRound
                && RobocodeScoreUtil.isLethalDamage(ourEnergyBeforeHit, creditedDamage)) {
            opponentScore += RobocodeScoreUtil.BULLET_KILL_BONUS_MULTIPLIER * enemyCreditedDamageOnUsThisRound;
            enemyKillBonusAppliedThisRound = true;
        }
    }

    public void onHitRobot(boolean myFault,
                           double enemyEnergyBeforeCollision,
                           double ourEnergyBeforeCollision) {
        if (myFault) {
            double creditedDamage = RobocodeScoreUtil.creditedRamDamage(enemyEnergyBeforeCollision);
            ourScore += RobocodeScoreUtil.ramDamageScore(creditedDamage);
            ourRammingDamageOnEnemyThisRound += creditedDamage;
            ourCreditedDamageOnEnemyThisRound += creditedDamage;
            if (!ourKillBonusAppliedThisRound
                    && RobocodeScoreUtil.isLethalDamage(enemyEnergyBeforeCollision, creditedDamage)) {
                ourScore += RobocodeScoreUtil.RAM_KILL_BONUS_MULTIPLIER * ourCreditedDamageOnEnemyThisRound;
                ourKillBonusAppliedThisRound = true;
            }
            return;
        }

        double creditedDamage = RobocodeScoreUtil.creditedRamDamage(ourEnergyBeforeCollision);
        opponentScore += RobocodeScoreUtil.ramDamageScore(creditedDamage);
        enemyRammingDamageOnUsThisRound += creditedDamage;
        enemyCreditedDamageOnUsThisRound += creditedDamage;
        if (!enemyKillBonusAppliedThisRound
                && RobocodeScoreUtil.isLethalDamage(ourEnergyBeforeCollision, creditedDamage)) {
            opponentScore += RobocodeScoreUtil.RAM_KILL_BONUS_MULTIPLIER * enemyCreditedDamageOnUsThisRound;
            enemyKillBonusAppliedThisRound = true;
        }
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
