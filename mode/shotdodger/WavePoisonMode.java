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
import oog.mega.saguaro.render.RenderState;
import robocode.ScannedRobotEvent;

public final class WavePoisonMode implements BattleMode {
    private final WavePoisonPlanner planner = new WavePoisonPlanner();
    private final ShotDodgerObservationProfile observationProfile;
    private final RoundOutcomeProfile roundOutcomeProfile = WavePoisonRoundOutcomeProfile.INSTANCE;
    private Info info;
    private BattleDataStore dataStore;

    public WavePoisonMode(ShotDodgerObservationProfile observationProfile) {
        if (observationProfile == null) {
            throw new IllegalArgumentException("WavePoisonMode requires a non-null observation profile");
        }
        this.observationProfile = observationProfile;
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
        robot.setBodyColor(new Color(136, 92, 28));
        robot.setGunColor(new Color(84, 56, 18));
        robot.setRadarColor(new Color(226, 176, 72));
        robot.setBulletColor(new Color(255, 208, 116));
        robot.setScanColor(new Color(244, 170, 52));
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
