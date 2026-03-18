package oog.mega.saguaro.movement;

import java.util.List;

import oog.mega.saguaro.math.PhysicsUtil;

final class PathBuildResult {
    final PhysicsUtil.Trajectory trajectory;
    final List<PathLeg> segmentLegs;
    final double firstTargetX;
    final double firstTargetY;
    final double firstTargetAngle;
    final int firstLegDurationTicks;

    PathBuildResult(PhysicsUtil.Trajectory trajectory,
                    List<PathLeg> segmentLegs,
                    double firstTargetX,
                    double firstTargetY,
                    double firstTargetAngle,
                    int firstLegDurationTicks) {
        this.trajectory = trajectory;
        this.segmentLegs = segmentLegs;
        this.firstTargetX = firstTargetX;
        this.firstTargetY = firstTargetY;
        this.firstTargetAngle = firstTargetAngle;
        this.firstLegDurationTicks = firstLegDurationTicks;
    }
}
