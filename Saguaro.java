package oog.mega.saguaro;

import java.awt.Graphics2D;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.ModeController;
import oog.mega.saguaro.radar.Radar;
import oog.mega.saguaro.render.RenderState;
import robocode.*;

public class Saguaro extends AdvancedRobot {
    private static final long PAINT_ACTIVITY_TIMEOUT_NANOS = 500_000_000L;
    private static final RenderState EMPTY_RENDER_STATE = new RenderState(null, false);
    private static final Info info = new Info();
    private static final Radar radar = new Radar();
    private static final ModeController mode = new ModeController();
    private static volatile long lastPaintHeartbeatNanos = Long.MIN_VALUE;
    private static volatile RenderState latestRenderState = EMPTY_RENDER_STATE;

    @Override
    public void run() {
        if (getRoundNum() == 0) {
            mode.startBattle();
        }
        lastPaintHeartbeatNanos = Long.MIN_VALUE;
        latestRenderState = EMPTY_RENDER_STATE;
        info.init(this, mode.getRoundOutcomeProfile(), mode.getObservationProfile(), mode.getDataStore());
        mode.init(info);
        radar.reset(this);
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);

        while (true) {
            info.updateTickState();
            radar.execute(info);

            BattlePlan plan = mode.getPlan();
            info.updateWaves();
            if (isPaintingActive()) {
                info.prepareWaveRenderState();
                latestRenderState = mode.getRenderState();
            }

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
        oog.mega.saguaro.mode.scoremax.PlanAudit.onWin();
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

    private int roundSkippedTurns;

    @Override
    public void onSkippedTurn(SkippedTurnEvent event) {
        roundSkippedTurns++;
    }

    @Override
    public void onBattleEnded(BattleEndedEvent event) {
        mode.saveCurrentBattle(this);
    }

    @Override
    public void onRoundEnded(RoundEndedEvent event) {
        if (BotConfig.Debug.PRINT_DIAGNOSTICS) {
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.RuleProfile.INSTANCE.diagnostics());
            out.println("Rule model: " + oog.mega.saguaro.mode.scoremax.PlanAudit.report());
        }
        if (getOthers() == 0 && getEnergy() > 0) oog.mega.saguaro.mode.scoremax.PlanAudit.onWin();
        oog.mega.saguaro.info.learning.rule.RoundWinCalibration.INSTANCE.endRound(oog.mega.saguaro.mode.scoremax.PlanAudit.roundWon(),
                info.getOurCreditedDamageOnEnemyThisRound(), info.getEnemyCreditedDamageOnUsThisRound());
        if (BotConfig.Debug.PRINT_DIAGNOSTICS) {
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.RoundWinCalibration.INSTANCE.diagnostics());
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.HorizonCalibration.INSTANCE.diagnostics());
        }
        oog.mega.saguaro.mode.scoremax.PlanAudit.finishRound(info.getOurCreditedDamageOnEnemyThisRound(),
                info.getEnemyCreditedDamageOnUsThisRound());
        if (BotConfig.Debug.PRINT_DIAGNOSTICS) {
            out.println("Rule model: " + oog.mega.saguaro.mode.scoremax.PlanAudit.raceCalibration());
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.RuleProfile.INSTANCE.recordDiagnostics());
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.RuleProfile.INSTANCE.weightDiagnostics());
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.RuleProfile.INSTANCE.dodgeDiagnostics());
            out.println("Rule model: " + oog.mega.saguaro.info.learning.rule.RuleProfile.INSTANCE.reachDiagnostics());
            out.println("Rule model: cost " + oog.mega.saguaro.info.learning.rule.RuleProfile.INSTANCE.costs()
                    + " turns=" + getTime());
            out.println("Gun budget: skippedTurns=" + roundSkippedTurns);
        }
        roundSkippedTurns = 0;
        mode.onRoundEnded();
    }

    @Override
    public void onPaint(Graphics2D g) {
        if (!BotConfig.Debug.ENABLE_WAVE_RENDERING || g == null) {
            return;
        }
        lastPaintHeartbeatNanos = System.nanoTime();
        info.renderWaves(g, latestRenderState);
    }

    private static boolean isPaintingActive() {
        if (!BotConfig.Debug.ENABLE_WAVE_RENDERING) {
            return false;
        }
        long heartbeat = lastPaintHeartbeatNanos;
        return heartbeat != Long.MIN_VALUE
                && System.nanoTime() - heartbeat <= PAINT_ACTIVITY_TIMEOUT_NANOS;
    }
}
