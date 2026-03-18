package oog.mega.saguaro;

import java.awt.Graphics2D;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.ModeController;
import oog.mega.saguaro.radar.Radar;
import robocode.*;

public class Saguaro extends AdvancedRobot {
    private static final boolean ENABLE_WAVE_RENDERING = true;
    private static Info info = new Info();
    private static Radar radar = new Radar();
    private static ModeController mode;

    @Override
    public void run() {
        if (getRoundNum() == 0 || mode == null) {
            mode = new ModeController();
            mode.startBattle();
        }
        info.init(this, mode.getRoundOutcomeProfile(), mode.getObservationProfile(), mode.getDataStore());
        mode.init(info);
        radar.reset(this);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        boolean hasGraphics = ENABLE_WAVE_RENDERING && getGraphics() != null;

        while (true) {
            info.updateAllEnemiesTick();
            radar.execute(info);

            BattlePlan plan = mode.getPlan();
            mode.applyColors(this);
            ModeController.RenderState renderState = mode.getRenderState();
            Graphics2D graphics = hasGraphics ? getGraphics() : null;

            info.updateWaves(
                    graphics,
                    renderState.selectedPath,
                    renderState.selectedSafeSpotPaths,
                    renderState.selectedPathIntersections,
                    renderState.debugLines,
                    renderState.renderDefaultWaveGraphics);

            setAhead(plan.moveDistance);
            setTurnRightRadians(plan.turnAngle);
            setTurnGunRightRadians(plan.gunTurnAngle);
            if (plan.firePower >= 0.1 && getGunHeat() == 0) {
                Bullet bullet = setFireBullet(plan.firePower);
                if (bullet != null) {
                    info.onBulletFired(bullet);
                }
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
        if (mode != null) {
            mode.onDeath();
        }
    }

    @Override
    public void onWin(WinEvent e) {
        info.onWin();
        if (mode != null) {
            mode.onWin();
        }
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
        if (mode != null) {
            mode.saveCurrentBattle(this);
        }
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        if (mode != null) {
            mode.onRoundEnded();
        }
    }

}
