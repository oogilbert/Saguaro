package oog.mega.saguaro.info.state;

import robocode.Rules;

public final class RobocodeScoreUtil {
    public static final double SURVIVAL_AND_LAST_BONUS_1V1 = 60.0;
    public static final double RAM_DAMAGE = 0.6;
    public static final double RAM_DAMAGE_SCORE_MULTIPLIER = 2.0;
    public static final double BULLET_KILL_BONUS_MULTIPLIER = 0.2;
    public static final double RAM_KILL_BONUS_MULTIPLIER = 0.3;
    private static final double LETHAL_EPSILON = 1e-9;

    private RobocodeScoreUtil() {
    }

    public static final class HitScoreScratch {
        public double creditedDamage;
        public double scoreDelta;
        public double cumulativeCreditedDamage;
        public boolean killBonusApplied;
    }

    public static double creditedBulletDamage(double bulletPower, double targetEnergyBeforeHit) {
        double nominalDamage = Rules.getBulletDamage(bulletPower);
        return Math.min(nominalDamage, Math.max(0.0, targetEnergyBeforeHit));
    }

    public static double creditedRamDamage(double targetEnergyBeforeCollision) {
        return Math.min(RAM_DAMAGE, Math.max(0.0, targetEnergyBeforeCollision));
    }

    public static double ramDamageScore(double creditedRamDamage) {
        return creditedRamDamage * RAM_DAMAGE_SCORE_MULTIPLIER;
    }

    public static double bulletKillBonus(double cumulativeCreditedDamage) {
        return cumulativeCreditedDamage * BULLET_KILL_BONUS_MULTIPLIER;
    }

    public static double ramKillBonus(double cumulativeCreditedDamage) {
        return cumulativeCreditedDamage * RAM_KILL_BONUS_MULTIPLIER;
    }

    public static void scoreBulletHit(double bulletPower,
                                      double targetEnergyBeforeHit,
                                      double cumulativeCreditedDamage,
                                      boolean killBonusAlreadyApplied,
                                      HitScoreScratch out) {
        double creditedDamage = creditedBulletDamage(bulletPower, targetEnergyBeforeHit);
        double updatedCumulativeCreditedDamage = cumulativeCreditedDamage + creditedDamage;
        double scoreDelta = creditedDamage;
        boolean killBonusApplied = killBonusAlreadyApplied;
        if (!killBonusApplied && isLethalDamage(targetEnergyBeforeHit, creditedDamage)) {
            scoreDelta += bulletKillBonus(updatedCumulativeCreditedDamage);
            killBonusApplied = true;
        }
        out.creditedDamage = creditedDamage;
        out.scoreDelta = scoreDelta;
        out.cumulativeCreditedDamage = updatedCumulativeCreditedDamage;
        out.killBonusApplied = killBonusApplied;
    }

    public static void scoreRamHit(double targetEnergyBeforeCollision,
                                   double cumulativeCreditedDamage,
                                   boolean killBonusAlreadyApplied,
                                   HitScoreScratch out) {
        double creditedDamage = creditedRamDamage(targetEnergyBeforeCollision);
        double updatedCumulativeCreditedDamage = cumulativeCreditedDamage + creditedDamage;
        double scoreDelta = ramDamageScore(creditedDamage);
        boolean killBonusApplied = killBonusAlreadyApplied;
        if (!killBonusApplied && isLethalDamage(targetEnergyBeforeCollision, creditedDamage)) {
            scoreDelta += ramKillBonus(updatedCumulativeCreditedDamage);
            killBonusApplied = true;
        }
        out.creditedDamage = creditedDamage;
        out.scoreDelta = scoreDelta;
        out.cumulativeCreditedDamage = updatedCumulativeCreditedDamage;
        out.killBonusApplied = killBonusApplied;
    }

    /**
     * This should probably allow crediting with lethal damage if the opponent is disabled, but currently this bug (present in other places too) 
     * is beneficial because it causes our bot to ram disabled opponents
     */
    private static boolean isLethalDamage(double targetEnergyBeforeHit, double creditedDamage) {
        return targetEnergyBeforeHit > 0.0
                && creditedDamage + LETHAL_EPSILON >= targetEnergyBeforeHit;
    }
}
