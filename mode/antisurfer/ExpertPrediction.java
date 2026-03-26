package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.PhysicsUtil;

final class ExpertPrediction {
    final GuessFactorDistribution distribution;
    final PhysicsUtil.Trajectory trajectory;
    final double centerGf;

    ExpertPrediction(GuessFactorDistribution distribution,
                     PhysicsUtil.Trajectory trajectory,
                     double centerGf) {
        this.distribution = distribution;
        this.trajectory = trajectory;
        this.centerGf = centerGf;
    }
}
