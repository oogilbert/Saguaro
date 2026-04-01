package oog.mega.saguaro.mode.shotdodger;

import java.awt.Color;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.info.persistence.BattleDataStore;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.render.RenderState;
import robocode.ScannedRobotEvent;

public final class WavePoisonMode implements BattleMode {
    private final WavePoisonPlanner planner;
    private final ShotDodgerObservationProfile observationProfile;
    private final RoundOutcomeProfile roundOutcomeProfile;
    private Info info;
    private BattleDataStore dataStore;

    public WavePoisonMode(ModeId modeId,
                          ShotDodgerObservationProfile observationProfile,
                          int prefireReverseTicks,
                          double stopCrawlDistance,
                          double prefireReverseDistance) {
        if (modeId == null) {
            throw new IllegalArgumentException("WavePoisonMode requires a non-null mode id");
        }
        if (observationProfile == null) {
            throw new IllegalArgumentException("WavePoisonMode requires a non-null observation profile");
        }
        this.observationProfile = observationProfile;
        this.roundOutcomeProfile = new WavePoisonRoundOutcomeProfile(modeId);
        this.planner = new WavePoisonPlanner(
                new WavePoisonPlanner.Config(prefireReverseTicks, stopCrawlDistance, prefireReverseDistance));
    }

    @Override
    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfile;
    }

    @Override
    public ModeObservationPolicy getObservationPolicy() {
        return ModeObservationPolicy.SHOT_DODGER_EXPERT_ONLY;
    }

    public ObservationProfile getObservationProfile() {
        return observationProfile;
    }

    @Override
    public void init(Info info, BattleServices services) {
        if (services == null) {
            throw new IllegalArgumentException("WavePoisonMode requires non-null battle services");
        }
        this.info = info;
        this.dataStore = services.dataStore();
        observationProfile.setInfo(info);
        planner.init(info, services.gun());
    }

    @Override
    public void onBattleEnded(Saguaro robot) {
        if (dataStore != null) {
            dataStore.requestDataSetSave(ShotDodgerDataSet.class);
        }
    }

    @Override
    public BattlePlan getPlan() {
        return planner.getBestPlan();
    }

    @Override
    public RenderState getRenderState() {
        return new RenderState(
                null,
                RenderState.WaveRenderMode.TICKS_ONLY,
                RenderState.WaveRenderMode.FULL,
                true);
    }

    @Override
    public void applyColors(Saguaro robot) {
        robot.setBodyColor(new Color(112, 120, 48));
        robot.setGunColor(new Color(72, 80, 28));
        robot.setRadarColor(new Color(188, 196, 92));
        robot.setBulletColor(new Color(220, 228, 128));
        robot.setScanColor(new Color(200, 208, 72));
    }

    @Override
    public void onScannedRobot(ScannedRobotEvent event) {
        if (info == null) {
            return;
        }
        observationProfile.onScannedRobot(info.getEnemy());
    }

    @Override
    public String describeSkippedTurnDiagnostics() {
        return planner.describeSkippedTurnDiagnostics();
    }
}
