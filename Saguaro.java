package oog.mega.saguaro;

import java.awt.Graphics2D;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.ModeController;
import oog.mega.saguaro.radar.Radar;
import oog.mega.saguaro.render.RenderState;
import robocode.*;

public class Saguaro extends AdvancedRobot {
    private static final Info info = new Info();
    private static final Radar radar = new Radar();
    private static final ModeController mode = new ModeController();
    private static boolean renderingEnabled;

    @Override
    public void run() {
        if (getRoundNum() == 0) {
            mode.startBattle();
            renderingEnabled = BotConfig.Debug.ENABLE_WAVE_RENDERING && isDebugMode();
        }
        info.init(this, mode.getRoundOutcomeProfile(), mode.getObservationProfile(), mode.getDataStore());
        mode.init(info);
        radar.reset(this);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        while (true) {
            info.updateTickState();
            radar.execute(info);

            BattlePlan plan = mode.getPlan();
            RenderState renderState = mode.getRenderState();
            Graphics2D graphics = renderingEnabled ? getGraphics() : null;

            info.updateWaves(
                    graphics,
                    renderState);

            setAhead(plan.moveDistance);
            setTurnRightRadians(plan.turnAngle);
            setTurnGunRightRadians(plan.gunTurnAngle);
            if (plan.firePower >= 0.1 && getEnergy() >= plan.firePower) {
                Bullet bullet = setFireBullet(plan.firePower);
                if (bullet != null) {
                    info.onBulletFired(bullet);
                }
                mode.onFireResult(bullet, plan);
            }

            execute();
        }
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        mode.onScannedRobot(e);
        info.onScannedRobot(e);
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        mode.onRobotDeath(e);
        info.onRobotDeath(e);
    }
    @Override
    public void onDeath(DeathEvent e) {
        info.onDeath();
        mode.onDeath();
    }

    @Override
    public void onWin(WinEvent e) {
        info.onWin();
        mode.onWin();
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        mode.onBulletHit(e);
        info.onBulletHit(e);
    }

    @Override
    public void onBulletHitBullet(BulletHitBulletEvent e) {
        mode.onBulletHitBullet(e);
        info.onBulletHitBullet(e);
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        mode.onHitByBullet(e);
        info.onHitByBullet(e);
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        mode.onHitRobot(e);
        info.onHitRobot(e);
    }

    @Override
    public void onSkippedTurn(SkippedTurnEvent event) {
    }

    @Override
    public void onBattleEnded(BattleEndedEvent event) {
        mode.saveCurrentBattle(this);
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        mode.onRoundEnded();
    }

    private static boolean isDebugMode() {
        try {
            return Boolean.getBoolean("debug");
        } catch (SecurityException e) {
            return false;
        }
    }
}
