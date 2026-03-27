package oog.mega.saguaro.mode.shotdodger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import oog.mega.saguaro.math.PhysicsUtil;

final class ShotDodgerExpertSnapshot {
    private final ExpertPrediction[] predictions = new ExpertPrediction[ShotDodgerExpertId.VALUES.length];

    void set(ShotDodgerExpertId expertId, ExpertPrediction prediction) {
        predictions[expertId.ordinal()] = prediction;
    }

    ExpertPrediction get(ShotDodgerExpertId expertId) {
        return predictions[expertId.ordinal()];
    }

    boolean isEmpty() {
        for (ExpertPrediction prediction : predictions) {
            if (prediction != null) {
                return false;
            }
        }
        return true;
    }

    int activeCount() {
        int count = 0;
        for (ExpertPrediction prediction : predictions) {
            if (prediction != null) {
                count++;
            }
        }
        return count;
    }

    List<ExpertPrediction> activePredictions() {
        List<ExpertPrediction> active = new ArrayList<ExpertPrediction>();
        for (ExpertPrediction prediction : predictions) {
            if (prediction != null) {
                active.add(prediction);
            }
        }
        return active;
    }

    double[] centers() {
        double[] centers = new double[predictions.length];
        Arrays.fill(centers, Double.NaN);
        for (ShotDodgerExpertId expertId : ShotDodgerExpertId.VALUES) {
            ExpertPrediction prediction = get(expertId);
            if (prediction != null) {
                centers[expertId.ordinal()] = prediction.centerGf;
            }
        }
        return centers;
    }

    PhysicsUtil.Trajectory nearestTrajectory(double targetGf) {
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
}
