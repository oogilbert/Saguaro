package oog.mega.saguaro.mode;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.gun.ShotSolution;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.math.MathUtils;

public final class DirectGunPlannerSupport {
    public static final double MIN_FIRE_POWER = 0.1;

    private DirectGunPlannerSupport() {
    }

    public static int firstFiringTickOffset(double currentGunHeat) {
        if (currentGunHeat < 0.001) {
            return 0;
        }
        return (int) Math.floor((currentGunHeat - 0.001) / 0.1) + 1;
    }

    public static double selectFirePower(Info info, RobotSnapshot robotState) {
        EnemyInfo enemy = info != null ? info.getEnemy() : null;
        if (enemy == null || robotState == null) {
            return MIN_FIRE_POWER;
        }
        double distance = enemy.distance;
        double enemyEnergy = enemy.energy;
        double ourEnergy = robotState.energy;
        return Math.max(MIN_FIRE_POWER, Math.min(3,
                distance < 100 ? 3 : Math.min(enemyEnergy / 4,
                        Math.min(ourEnergy / 10, 1 + 400 / distance))));
    }

    public static double sanitizeFirePower(double firePower, double availableEnergy) {
        if (!(firePower >= MIN_FIRE_POWER) || !(availableEnergy >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.min(3.0, Math.min(firePower, availableEnergy));
    }

    public static GunInstruction buildGunInstruction(Info info,
                                                     GunController gun,
                                                     double shooterX,
                                                     double shooterY,
                                                     double gunHeadingAtDecision,
                                                     double availableEnergy,
                                                     long fireTime,
                                                     int ticksUntilFire,
                                                     double selectedFirePower) {
        double firePower = sanitizeFirePower(selectedFirePower, availableEnergy);
        EnemyInfo.PredictedPosition enemyAtFireTime = predictEnemyPositionAt(info, fireTime);
        if (enemyAtFireTime == null) {
            return new GunInstruction(0.0, 0.0);
        }

        double aimPower = firePower >= MIN_FIRE_POWER
                ? firePower
                : Math.min(BotConfig.ScoreMax.DEFAULT_TRACKING_FIRE_POWER, availableEnergy);
        ShotSolution shot = gun.selectOptimalShotFromPosition(
                shooterX,
                shooterY,
                enemyAtFireTime.x,
                enemyAtFireTime.y,
                aimPower,
                gunHeadingAtDecision,
                ticksUntilFire);
        if (shot == null || !Double.isFinite(shot.firingAngle)) {
            shot = gun.selectOptimalUnconstrainedShotFromPosition(
                    shooterX,
                    shooterY,
                    enemyAtFireTime.x,
                    enemyAtFireTime.y,
                    aimPower,
                    ticksUntilFire);
        }
        if (shot == null || !Double.isFinite(shot.firingAngle)) {
            shot = new ShotSolution(0.0, gunHeadingAtDecision);
        }

        double gunTurnAngle = MathUtils.normalizeAngle(shot.firingAngle - gunHeadingAtDecision);
        double fireNowPower = ticksUntilFire == 0 ? firePower : 0.0;
        return new GunInstruction(gunTurnAngle, fireNowPower);
    }

    private static EnemyInfo.PredictedPosition predictEnemyPositionAt(Info info, long time) {
        EnemyInfo enemy = info != null ? info.getEnemy() : null;
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        return enemy.predictPositionAtTime(
                time,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    public static final class GunInstruction {
        public final double gunTurnAngle;
        public final double firePower;

        public GunInstruction(double gunTurnAngle, double firePower) {
            this.gunTurnAngle = gunTurnAngle;
            this.firePower = firePower;
        }
    }
}
