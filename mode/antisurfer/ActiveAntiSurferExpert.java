package oog.mega.saguaro.mode.antisurfer;

import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.PhysicsUtil;

final class ActiveAntiSurferExpert {
    private static final double ENSEMBLE_BANDWIDTH = 0.4;

    private ActiveAntiSurferExpert() {
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context,
                                                EnemyInfo enemy,
                                                Info info) {
        return createEnsemblePrediction(createGunAllPredictions(context, enemy, info));
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     Info info) {
        return createEnsemblePrediction(createMovementAllPredictions(context, info));
    }

    static double[] createGunCenters(WaveContextFeatures.WaveContext context,
                                     EnemyInfo enemy,
                                     Info info) {
        return extractCenters(createGunAllPredictions(context, enemy, info));
    }

    static double[] createMovementCenters(WaveContextFeatures.WaveContext context,
                                          Info info) {
        return extractCenters(createMovementAllPredictions(context, info));
    }

    private static ExpertPrediction createEnsemblePrediction(List<ExpertPrediction> predictions) {
        if (predictions == null || predictions.isEmpty()) {
            return null;
        }
        double[] centers = new double[predictions.size()];
        for (int i = 0; i < predictions.size(); i++) {
            centers[i] = predictions.get(i).centerGf;
        }
        KDEDistribution distribution = new KDEDistribution(centers, ENSEMBLE_BANDWIDTH);
        double peakGf = distribution.findPeakGF();
        PhysicsUtil.Trajectory trajectory = nearestTrajectory(predictions, peakGf);
        return new ExpertPrediction(distribution, trajectory, peakGf);
    }

    private static PhysicsUtil.Trajectory nearestTrajectory(List<ExpertPrediction> predictions,
                                                            double targetGf) {
        PhysicsUtil.Trajectory bestTrajectory = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (ExpertPrediction prediction : predictions) {
            if (prediction == null || prediction.trajectory == null) {
                continue;
            }
            double distance = Math.abs(prediction.centerGf - targetGf);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestTrajectory = prediction.trajectory;
            }
        }
        return bestTrajectory;
    }

    private static List<ExpertPrediction> createGunAllPredictions(WaveContextFeatures.WaveContext context,
                                                                  EnemyInfo enemy,
                                                                  Info info) {
        return AntiSurferSourceExpertCatalog.createGunAllPredictions(context, enemy, info);
    }

    private static List<ExpertPrediction> createMovementAllPredictions(WaveContextFeatures.WaveContext context,
                                                                       Info info) {
        return AntiSurferSourceExpertCatalog.createMovementAllPredictions(context, info);
    }

    private static double[] extractCenters(List<ExpertPrediction> predictions) {
        if (predictions == null || predictions.isEmpty()) {
            return null;
        }
        double[] centers = new double[predictions.size()];
        for (int i = 0; i < predictions.size(); i++) {
            centers[i] = predictions.get(i).centerGf;
        }
        return centers;
    }
}
