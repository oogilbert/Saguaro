package oog.mega.saguaro.mode.antibasicsurfer;

import java.awt.Color;
import java.util.Collections;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.gun.GunController;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.mode.scoremax.ScoreMaxPlanner;
import oog.mega.saguaro.movement.MovementController;
import oog.mega.saguaro.render.PathOverlay;
import oog.mega.saguaro.render.RenderState;

public final class AntiBasicSurferMode implements BattleMode {
    private static final Color SELECTED_PATH_LINE_COLOR = new Color(220, 120, 50);
    private static final Color SELECTED_PATH_NODE_COLOR = new Color(220, 120, 50, 210);
    private static final Color SELECTED_PATH_MARKER_COLOR = new Color(255, 200, 100);
    private static final float SELECTED_PATH_STROKE_WIDTH = 2.0f;

    private final ScoreMaxPlanner planner = new ScoreMaxPlanner(ScoreMaxPlanner.Config.ANTI_BASIC_SURFER);
    private final RoundOutcomeProfile roundOutcomeProfile =
            new AntiBasicSurferRoundOutcomeProfile(ModeId.ANTI_BASIC_SURFER);
    private MovementController movement;
    private GunController gun;

    @Override
    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfile;
    }

    @Override
    public ModeObservationPolicy getObservationPolicy() {
        return ModeObservationPolicy.MOVEMENT_FULL_TARGETING_READ_ONLY;
    }

    @Override
    public void init(Info info, BattleServices services) {
        movement = services.movement();
        gun = services.gun();
        planner.init(info, movement, gun);
    }

    @Override
    public BattlePlan getPlan() {
        return planner.getBestPlan();
    }

    @Override
    public RenderState getRenderState() {
        PathOverlay overlay = PathOverlay.forCandidatePath(
                planner.getLastSelectedPath(),
                planner.getLastSelectedPathIntersections(),
                SELECTED_PATH_LINE_COLOR,
                SELECTED_PATH_NODE_COLOR,
                SELECTED_PATH_MARKER_COLOR,
                SELECTED_PATH_STROKE_WIDTH);
        return new RenderState(
                overlay != null
                        ? Collections.singletonList(overlay)
                        : Collections.<PathOverlay>emptyList());
    }

    @Override
    public void applyColors(Saguaro robot) {
        robot.setBodyColor(new Color(180, 80, 40));
        robot.setGunColor(new Color(120, 50, 25));
        robot.setRadarColor(new Color(220, 140, 60));
        robot.setBulletColor(new Color(255, 180, 80));
        robot.setScanColor(new Color(240, 160, 60));
    }

    @Override
    public String describeSkippedTurnDiagnostics() {
        return planner.describeSkippedTurnDiagnostics();
    }
}
