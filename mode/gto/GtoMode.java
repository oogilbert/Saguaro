package oog.mega.saguaro.mode.gto;

import java.awt.Color;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.NoOpLearningProfile;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.mode.BattleMode;
import oog.mega.saguaro.mode.BattlePlan;
import oog.mega.saguaro.mode.BattleServices;
import oog.mega.saguaro.mode.ModeController;

public final class GtoMode implements BattleMode {
    private static final BattlePlan HOLD_POSITION_PLAN = new BattlePlan(0.0, 0.0, 0.0, 0.0);
    private static final ModeObservationPolicy OBSERVATION_POLICY =
            new ModeObservationPolicy(true, true, true, false, true, false, true, false);
    private final RoundOutcomeProfile roundOutcomeProfile = NoOpLearningProfile.INSTANCE;

    @Override
    public RoundOutcomeProfile getRoundOutcomeProfile() {
        return roundOutcomeProfile;
    }

    @Override
    public ModeObservationPolicy getObservationPolicy() {
        return OBSERVATION_POLICY;
    }

    @Override
    public void init(Info info, BattleServices services) {
        if (info == null || services == null) {
            throw new IllegalArgumentException("GtoMode requires non-null info and services");
        }
    }

    @Override
    public BattlePlan getPlan() {
        return HOLD_POSITION_PLAN;
    }

    @Override
    public ModeController.RenderState getRenderState() {
        return new ModeController.RenderState(null, null, null);
    }

    @Override
    public void applyColors(Saguaro robot) {
        robot.setBodyColor(new Color(34, 64, 102));
        robot.setGunColor(new Color(22, 41, 74));
        robot.setRadarColor(new Color(79, 118, 168));
        robot.setBulletColor(new Color(118, 178, 242));
        robot.setScanColor(new Color(142, 198, 255));
    }
}
