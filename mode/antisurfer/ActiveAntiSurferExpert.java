package oog.mega.saguaro.mode.antisurfer;

import java.util.Arrays;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.PhysicsUtil;

final class ActiveAntiSurferExpert {
    private static final double ENSEMBLE_BANDWIDTH = 0.4;

    private ActiveAntiSurferExpert() {
    }

    static ExpertPrediction createEnsemblePrediction(AntiSurferExpertSnapshot snapshot,
                                                     double[] perExpertWeights) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }

        int activeCount = snapshot.activeCount();
        double[] centers = new double[activeCount];
        double[] weights = new double[activeCount];
        boolean anyPositiveWeight = false;
        int index = 0;
        for (AntiSurferExpertId expertId : AntiSurferExpertId.VALUES) {
            ExpertPrediction prediction = snapshot.get(expertId);
            if (prediction == null) {
                continue;
            }
            centers[index] = prediction.centerGf;
            double weight = perExpertWeights != null
                    && expertId.ordinal() < perExpertWeights.length
                    && Double.isFinite(perExpertWeights[expertId.ordinal()])
                    ? Math.max(0.0, perExpertWeights[expertId.ordinal()])
                    : 1.0;
            weights[index] = weight;
            anyPositiveWeight |= weight > 1e-9;
            index++;
        }
        if (!anyPositiveWeight) {
            Arrays.fill(weights, 1.0);
        }

        KDEDistribution distribution = new KDEDistribution(centers, weights, ENSEMBLE_BANDWIDTH);
        double peakGf = distribution.findPeakGF();
        PhysicsUtil.Trajectory trajectory = snapshot.nearestTrajectory(peakGf);
        return new ExpertPrediction(distribution, trajectory, peakGf);
    }
}
