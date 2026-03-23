package oog.mega.saguaro.radar;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import robocode.util.Utils;

public class Radar {
    private int radarTurnDirection = 1;
    private int radarReversals = 0;

    public void execute(Info info) {
        Saguaro robot = info.getRobot();
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            robot.setTurnRadarRightRadians(radarTurnDirection * Double.POSITIVE_INFINITY);
            return;
        }

        long currentTime = robot.getTime();
        if (enemy.lastScanTime == currentTime) {
            radarReversals = 0;
            double radarTurn = Utils.normalRelativeAngle(enemy.absoluteBearing - robot.getRadarHeadingRadians());
            int direction = nonZeroSign(radarTurn, radarTurnDirection);
            radarTurnDirection = direction;
            robot.setTurnRadarRightRadians(radarTurn + direction * BotConfig.Radar.SCAN_WIDTH);
            return;
        }

        // If scan was missed (for example due skipped turns), sweep back through the last known bearing
        // a few times before falling back to a continuous full sweep.
        if (radarReversals < BotConfig.Radar.REVERSALS_BEFORE_SWEEP) {
            double radarTurn = Utils.normalRelativeAngle(enemy.absoluteBearing - robot.getRadarHeadingRadians());
            int newDirection = nonZeroSign(radarTurn, radarTurnDirection);
            if (newDirection != radarTurnDirection) {
                radarTurnDirection = newDirection;
                radarReversals++;
            }
        }
        robot.setTurnRadarRightRadians(radarTurnDirection * Double.POSITIVE_INFINITY);
    }

    public void reset(Saguaro robot) {
        double centerX = robot.getBattleFieldWidth() * 0.5;
        double centerY = robot.getBattleFieldHeight() * 0.5;
        double centerBearing = Math.atan2(centerX - robot.getX(), centerY - robot.getY());
        double radarTurn = Utils.normalRelativeAngle(centerBearing - robot.getRadarHeadingRadians());
        radarTurnDirection = nonZeroSign(radarTurn, 1);
        radarReversals = 0;
    }

    private static int nonZeroSign(double value, int fallback) {
        if (value > 1e-12) {
            return 1;
        }
        if (value < -1e-12) {
            return -1;
        }
        return fallback == 0 ? 1 : fallback;
    }
}
