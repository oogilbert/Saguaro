package oog.mega.saguaro.info.state;

import robocode.Rules;

public final class RobocodeScoreUtil {
    public static final double SURVIVAL_BONUS = 50.0;
    public static final double LAST_SURVIVOR_BONUS_1V1 = 10.0;
    public static final double SURVIVAL_AND_LAST_BONUS_1V1 = SURVIVAL_BONUS + LAST_SURVIVOR_BONUS_1V1;
    public static final double MAX_RAM_DAMAGE = 0.6;
    public static final double RAM_DAMAGE_SCORE_MULTIPLIER = 2.0;
    public static final double BULLET_KILL_BONUS_MULTIPLIER = 0.2;
    public static final double RAM_KILL_BONUS_MULTIPLIER = 0.3;
    private static final double LETHAL_EPSILON = 1e-9;

    private RobocodeScoreUtil() {
    }

    public static double creditedBulletDamage(double bulletPower, double targetEnergyBeforeHit) {
        double nominalDamage = Rules.getBulletDamage(bulletPower);
        if (!Double.isFinite(targetEnergyBeforeHit)) {
            return nominalDamage;
        }
        return Math.min(nominalDamage, Math.max(0.0, targetEnergyBeforeHit));
    }

    public static double creditedRamDamage(double targetEnergyBeforeCollision) {
        if (!Double.isFinite(targetEnergyBeforeCollision)) {
            return MAX_RAM_DAMAGE;
        }
        return Math.min(MAX_RAM_DAMAGE, Math.max(0.0, targetEnergyBeforeCollision));
    }

    public static double ramDamageScore(double creditedRamDamage) {
        return Math.max(0.0, creditedRamDamage) * RAM_DAMAGE_SCORE_MULTIPLIER;
    }

    public static double powerToDealDamage(double damage) {
        if (!Double.isFinite(damage)) {
            return Rules.MAX_BULLET_POWER;
        }
        double clampedDamage = Math.max(0.0, damage);
        double requiredPower = clampedDamage <= 4.0
                ? clampedDamage / 4.0
                : (clampedDamage + 2.0) / 6.0;
        return Math.max(Rules.MIN_BULLET_POWER, Math.min(Rules.MAX_BULLET_POWER, requiredPower));
    }

    public static boolean isLethalDamage(double targetEnergyBeforeHit, double creditedDamage) {
        return Double.isFinite(targetEnergyBeforeHit)
                && targetEnergyBeforeHit > 0.0
                && creditedDamage + LETHAL_EPSILON >= targetEnergyBeforeHit;
    }
}
