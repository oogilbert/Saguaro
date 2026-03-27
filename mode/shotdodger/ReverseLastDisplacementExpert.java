package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;

final class ReverseLastDisplacementExpert {
    private static final double RANGE_EPSILON = 1e-9;

    private ReverseLastDisplacementExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context,
                                                         EnemyInfo enemy) {
        ExpertPrediction prediction = createGunPrediction(context, enemy);
        return prediction != null ? prediction.distribution : null;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context,
                                                              Info info) {
        ExpertPrediction prediction = createMovementPrediction(context, info);
        return prediction != null ? prediction.distribution : null;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context,
                                                EnemyInfo enemy) {
        ExpertPrediction basePrediction = LastDisplacementExpert.createGunPrediction(context, enemy);
        return createPrediction(context, basePrediction, BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH);
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     Info info) {
        ExpertPrediction basePrediction = LastDisplacementExpert.createMovementPrediction(context, info);
        return createPrediction(context, basePrediction, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH);
    }

    private static ExpertPrediction createPrediction(WaveContextFeatures.WaveContext context,
                                                     ExpertPrediction basePrediction,
                                                     double bandwidth) {
        if (basePrediction == null) {
            return null;
        }
        double reverseGf = ShotDodgerPreciseMea.clampGf(
                context,
                reverseAcrossPreciseMea(context, basePrediction.centerGf));
        GuessFactorDistribution distribution = new KDEDistribution(
                new double[]{reverseGf},
                bandwidth);
        return new ExpertPrediction(distribution, basePrediction.trajectory, reverseGf);
    }

    private static double reverseAcrossPreciseMea(WaveContextFeatures.WaveContext context,
                                                  double gf) {
        double[] preciseRange = ShotDodgerPreciseMea.range(context);
        double minGf = preciseRange[0];
        double maxGf = preciseRange[1];
        double clampedGf = Math.max(minGf, Math.min(maxGf, gf));
        if (Math.abs(clampedGf) <= RANGE_EPSILON) {
            return 0.0;
        }

        double normalizedPreciseGf;
        if (clampedGf > 0.0) {
            if (maxGf <= RANGE_EPSILON) {
                return 0.0;
            }
            normalizedPreciseGf = clampedGf / maxGf;
        } else {
            double negativeMagnitude = -minGf;
            if (negativeMagnitude <= RANGE_EPSILON) {
                return 0.0;
            }
            normalizedPreciseGf = clampedGf / negativeMagnitude;
        }

        double reversedNormalizedPreciseGf = -normalizedPreciseGf;
        if (reversedNormalizedPreciseGf > 0.0) {
            if (maxGf <= RANGE_EPSILON) {
                return 0.0;
            }
            return Math.max(minGf, Math.min(maxGf, reversedNormalizedPreciseGf * maxGf));
        }
        double negativeMagnitude = -minGf;
        if (negativeMagnitude <= RANGE_EPSILON) {
            return 0.0;
        }
        return Math.max(minGf, Math.min(maxGf, reversedNormalizedPreciseGf * negativeMagnitude));
    }
}
