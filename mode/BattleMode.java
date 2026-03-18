package oog.mega.saguaro.mode;

import oog.mega.saguaro.Saguaro;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ModeObservationPolicy;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import robocode.BulletHitEvent;
import robocode.BulletHitBulletEvent;
import robocode.HitByBulletEvent;
import robocode.HitRobotEvent;
import robocode.RobotDeathEvent;
import robocode.ScannedRobotEvent;

public interface BattleMode {
    RoundOutcomeProfile getRoundOutcomeProfile();

    default ModeObservationPolicy getObservationPolicy() {
        return ModeObservationPolicy.FULL;
    }

    void init(Info info, BattleServices services);

    BattlePlan getPlan();

    ModeController.RenderState getRenderState();

    void applyColors(Saguaro robot);

    default void onBattleEnded(Saguaro robot) {
    }

    default void onBulletHit(BulletHitEvent event) {
    }

    default void onBulletHitBullet(BulletHitBulletEvent event) {
    }

    default void onHitByBullet(HitByBulletEvent event) {
    }

    default void onHitRobot(HitRobotEvent event) {
    }

    default void onScannedRobot(ScannedRobotEvent event) {
    }

    default void onRobotDeath(RobotDeathEvent event) {
    }

    default String describeSkippedTurnDiagnostics() {
        return getClass().getSimpleName();
    }
}
