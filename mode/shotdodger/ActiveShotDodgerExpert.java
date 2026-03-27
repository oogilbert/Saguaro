package oog.mega.saguaro.mode.shotdodger;

final class ActiveShotDodgerExpert {
    private ActiveShotDodgerExpert() {
    }

    static ExpertPrediction createEnsemblePrediction(ShotDodgerExpertSnapshot snapshot,
                                                     double[] perExpertWeights) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }

        ExpertPrediction bestPrediction = null;
        double bestWeight = Double.NEGATIVE_INFINITY;
        for (ShotDodgerExpertId expertId : ShotDodgerExpertId.VALUES) {
            ExpertPrediction prediction = snapshot.get(expertId);
            if (prediction == null) {
                continue;
            }
            double weight = perExpertWeights != null
                    && expertId.ordinal() < perExpertWeights.length
                    && Double.isFinite(perExpertWeights[expertId.ordinal()])
                    ? Math.max(0.0, perExpertWeights[expertId.ordinal()])
                    : 1.0;
            if (weight > bestWeight) {
                bestWeight = weight;
                bestPrediction = prediction;
            }
        }
        return bestPrediction;
    }
}
