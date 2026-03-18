package oog.mega.saguaro.mode.scoremax;

final class WinProbabilityModel {
    private static final double ROUND_START_TOTAL_ENERGY_1V1 = 200.0;

    static final class Params {
        final double priorWinProbabilityAtFullEvenEnergy;
        final double priorWeightFloor;
        final double priorWeightExponent;
        final double energyLeadScale;
        final double energyLeadOffset;
        final double lowEnergyUncertaintyScale;
        final double priorLogit;

        Params(double priorWinProbabilityAtFullEvenEnergy,
               double priorWeightFloor,
               double priorWeightExponent,
               double energyLeadScale,
               double energyLeadOffset,
               double lowEnergyUncertaintyScale) {
            if (priorWinProbabilityAtFullEvenEnergy <= 0.0 || priorWinProbabilityAtFullEvenEnergy >= 1.0) {
                throw new IllegalArgumentException(
                        "priorWinProbabilityAtFullEvenEnergy must be in (0,1): "
                                + priorWinProbabilityAtFullEvenEnergy);
            }
            if (priorWeightFloor < 0.0 || priorWeightFloor > 1.0) {
                throw new IllegalArgumentException("priorWeightFloor must be in [0,1]: " + priorWeightFloor);
            }
            if (priorWeightExponent <= 0.0) {
                throw new IllegalArgumentException("priorWeightExponent must be > 0: " + priorWeightExponent);
            }
            if (energyLeadScale <= 0.0) {
                throw new IllegalArgumentException("energyLeadScale must be > 0: " + energyLeadScale);
            }
            if (energyLeadOffset <= 0.0) {
                throw new IllegalArgumentException("energyLeadOffset must be > 0: " + energyLeadOffset);
            }
            if (lowEnergyUncertaintyScale < 0.0) {
                throw new IllegalArgumentException(
                        "lowEnergyUncertaintyScale must be >= 0: " + lowEnergyUncertaintyScale);
            }

            this.priorWinProbabilityAtFullEvenEnergy = priorWinProbabilityAtFullEvenEnergy;
            this.priorWeightFloor = priorWeightFloor;
            this.priorWeightExponent = priorWeightExponent;
            this.energyLeadScale = energyLeadScale;
            this.energyLeadOffset = energyLeadOffset;
            this.lowEnergyUncertaintyScale = lowEnergyUncertaintyScale;
            this.priorLogit = logit(priorWinProbabilityAtFullEvenEnergy);
        }
    }

    static final class Projection {
        final double currentWinProbability;
        final double projectedWinProbability;

        Projection(double currentWinProbability, double projectedWinProbability) {
            this.currentWinProbability = currentWinProbability;
            this.projectedWinProbability = projectedWinProbability;
        }
    }

    private WinProbabilityModel() {
    }

    static double estimateWinProbability(double ourEnergy, double enemyEnergy, Params params) {
        double clampedOurEnergy = Math.max(0.0, ourEnergy);
        double clampedEnemyEnergy = Math.max(0.0, enemyEnergy);
        double totalEnergy = clampedOurEnergy + clampedEnemyEnergy;
        double energyRatio = Math.max(0.0, Math.min(1.0, totalEnergy / ROUND_START_TOTAL_ENERGY_1V1));
        double priorWeight = params.priorWeightFloor
                + (1.0 - params.priorWeightFloor) * Math.pow(energyRatio, params.priorWeightExponent);

        // At low total energy, damp lead sensitivity to reflect unresolved bullets already in flight.
        double lowEnergyUncertainty = params.lowEnergyUncertaintyScale * (1.0 - energyRatio);
        double leadDenominator = totalEnergy + params.energyLeadOffset + lowEnergyUncertainty;
        double leadTerm = params.energyLeadScale * (clampedOurEnergy - clampedEnemyEnergy) / leadDenominator;
        double z = priorWeight * params.priorLogit + leadTerm;
        return sigmoid(z);
    }

    private static double logit(double probability) {
        return Math.log(probability / (1.0 - probability));
    }

    private static double sigmoid(double x) {
        if (x >= 0) {
            double e = Math.exp(-x);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(x);
        return e / (1.0 + e);
    }
}
