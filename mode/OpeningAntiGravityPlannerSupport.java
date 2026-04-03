package oog.mega.saguaro.mode;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;

public final class OpeningAntiGravityPlannerSupport {
    private static final double OPENING_ANTI_GRAVITY_COMMAND_DISTANCE = 1000.0;

    private OpeningAntiGravityPlannerSupport() {
    }

    public static MovementInstruction maybeBuildMovementInstruction(Info info,
                                                                    RobotSnapshot robotState) {
        if (info == null || robotState == null) {
            return null;
        }
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        if (Double.isFinite(enemy.lastDetectedBulletPower)) {
            return null;
        }
        if (!(robotState.gunCoolingRate > 0.0)) {
            return null;
        }

        int ticksUntilEnemyGunReady = ticksUntilGunReady(enemy.gunHeat, robotState.gunCoolingRate);
        if (ticksUntilEnemyGunReady <= BotConfig.Movement.OPENING_ANTI_GRAVITY_STOP_LEAD_TICKS) {
            return null;
        }

        double desiredHeading = openingAntiGravityHeading(
                robotState.x,
                robotState.y,
                enemy.x,
                enemy.y,
                enemy.absoluteBearing,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return movementInstructionForHeading(robotState.heading, desiredHeading);
    }

    public static double directGunTurnToEnemy(Info info, RobotSnapshot robotState) {
        if (info == null || robotState == null) {
            return 0.0;
        }
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound || !Double.isFinite(enemy.absoluteBearing)) {
            return 0.0;
        }
        return MathUtils.normalizeAngle(enemy.absoluteBearing - robotState.gunHeading);
    }

    public static final class MovementInstruction {
        public final double moveDistance;
        public final double turnAngle;

        private MovementInstruction(double moveDistance, double turnAngle) {
            this.moveDistance = moveDistance;
            this.turnAngle = turnAngle;
        }
    }

    private static int ticksUntilGunReady(double gunHeat, double gunCoolingRate) {
        if (gunHeat <= BotConfig.GUN_HEAT_READY_EPSILON) {
            return 0;
        }
        return (int) Math.ceil((gunHeat - BotConfig.GUN_HEAT_READY_EPSILON) / gunCoolingRate);
    }

    private static double openingAntiGravityHeading(double myX,
                                                    double myY,
                                                    double enemyX,
                                                    double enemyY,
                                                    double enemyAbsoluteBearing,
                                                    double battlefieldWidth,
                                                    double battlefieldHeight) {
        double margin = PhysicsUtil.WALL_MARGIN;

        double leftDistance = Math.max(1.0, myX - margin);
        double rightDistance = Math.max(1.0, battlefieldWidth - margin - myX);
        double bottomDistance = Math.max(1.0, myY - margin);
        double topDistance = Math.max(1.0, battlefieldHeight - margin - myY);

        double forceX = 1.0 / (leftDistance * leftDistance) - 1.0 / (rightDistance * rightDistance);
        double forceY = 1.0 / (bottomDistance * bottomDistance) - 1.0 / (topDistance * topDistance);

        double enemyDx = myX - enemyX;
        double enemyDy = myY - enemyY;
        double enemyDistanceSquared = enemyDx * enemyDx + enemyDy * enemyDy;
        if (enemyDistanceSquared > 1e-9) {
            double enemyDistance = Math.sqrt(enemyDistanceSquared);
            double enemyForceScale = 2.0 / (enemyDistanceSquared * enemyDistance);
            forceX += enemyDx * enemyForceScale;
            forceY += enemyDy * enemyForceScale;
        }

        if (Math.abs(forceX) <= 1e-12 && Math.abs(forceY) <= 1e-12) {
            return MathUtils.normalizeAngle(enemyAbsoluteBearing + Math.PI);
        }
        return absoluteBearing(0.0, 0.0, forceX, forceY);
    }

    private static MovementInstruction movementInstructionForHeading(double currentHeading,
                                                                     double desiredHeading) {
        double turnAngle = MathUtils.normalizeAngle(desiredHeading - currentHeading);
        double moveDistance = OPENING_ANTI_GRAVITY_COMMAND_DISTANCE;
        if (Math.abs(turnAngle) > 0.5 * Math.PI) {
            turnAngle = MathUtils.normalizeAngle(turnAngle + Math.PI);
            moveDistance = -OPENING_ANTI_GRAVITY_COMMAND_DISTANCE;
        }
        return new MovementInstruction(moveDistance, turnAngle);
    }

    private static double absoluteBearing(double sourceX, double sourceY, double targetX, double targetY) {
        return Math.atan2(targetX - sourceX, targetY - sourceY);
    }
}
