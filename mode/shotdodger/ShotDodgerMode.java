package oog.mega.saguaro.mode.shotdodger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.ObservationProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.render.PathOverlay;
import oog.mega.saguaro.render.RenderState;

public final class ShotDodgerMode implements BattleMode {
    private static final Color SELECTED_PATH_LINE_COLOR = new Color(220, 120, 40);
    private static final Color SELECTED_PATH_NODE_COLOR = new Color(220, 120, 40, 210);
    private static final Color SELECTED_PATH_MARKER_COLOR = new Color(255, 200, 140);
    private static final float SELECTED_PATH_STROKE_WIDTH = 2.0f;

    private final ShotDodgerPlanner planner = new ShotDodgerPlanner();
    private final ShotDodgerObservationProfile observationProfile = new ShotDodgerObservationProfile();
    private final RoundOutcomeProfile roundOutcomeProfile = ShotDodgerRoundOutcomeProfile.INSTANCE;
    private Info info;

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
        this.info = info;
        observationProfile.setInfo(info);
        planner.init(info, services.movement(), services.gun(), observationProfile);
    }

    @Override
    public BattlePlan getPlan() {
        return planner.getBestPlan();
    }

    @Override
    public RenderState getRenderState() {
        List<PathOverlay> overlays = new ArrayList<PathOverlay>();
        PathOverlay overlay = PathOverlay.forCandidatePath(
                planner.getLastSelectedPath(),
                planner.getLastSelectedPathIntersections(),
                SELECTED_PATH_LINE_COLOR,
                SELECTED_PATH_NODE_COLOR,
                SELECTED_PATH_MARKER_COLOR,
                SELECTED_PATH_STROKE_WIDTH);
        if (overlay != null) {
            overlays.add(overlay);
        }
        return new RenderState(
                overlays);
    }

    @Override
    public void applyColors(Saguaro robot) {
        robot.setBodyColor(new Color(178, 76, 54));
        robot.setGunColor(new Color(100, 40, 30));
        robot.setRadarColor(new Color(210, 140, 60));
        robot.setBulletColor(new Color(255, 170, 80));
        robot.setScanColor(new Color(240, 130, 50));
    }

    @Override
    public String describeSkippedTurnDiagnostics() {
        return planner.describeSkippedTurnDiagnostics();
    }
}
