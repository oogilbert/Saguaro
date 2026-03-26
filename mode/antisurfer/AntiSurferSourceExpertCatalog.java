package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class AntiSurferSourceExpertCatalog {
    private AntiSurferSourceExpertCatalog() {
    }

    static List<ExpertPrediction> createGunSourcePredictions(WaveContextFeatures.WaveContext context,
                                                             EnemyInfo enemy,
                                                             Info info) {
        List<ExpertPrediction> predictions = new ArrayList<ExpertPrediction>();
        addIfPresent(predictions, StateContinuationExpert.createGunPrediction(context));
        addIfPresent(predictions, MiniPatternRepeaterExpert.createGunPrediction(context, enemy));
        addIfPresent(predictions, AlternatingMiniPatternRepeaterExpert.createGunPrediction(context, enemy));
        addIfPresent(predictions, StopAimerExpert.createGunPrediction(context));
        addIfPresent(predictions, EscapeAheadExpert.createGunPrediction(context));
        addIfPresent(predictions, EscapeReverseExpert.createGunPrediction(context));
        addIfPresent(predictions, LastDisplacementExpert.createGunPrediction(context, enemy));
        addIfPresent(predictions, ReverseLastDisplacementExpert.createGunPrediction(context, enemy));
        addIfPresent(predictions, HalfAheadExpert.createGunPrediction(context));
        addIfPresent(predictions, HalfReverseExpert.createGunPrediction(context));
        addIfPresent(predictions, CenterOfMassExpert.createGunPrediction(context));
        return predictions;
    }

    static List<ExpertPrediction> createMovementSourcePredictions(WaveContextFeatures.WaveContext context,
                                                                  Info info) {
        List<ExpertPrediction> predictions = new ArrayList<ExpertPrediction>();
        addIfPresent(predictions, StateContinuationExpert.createMovementPrediction(context));
        addIfPresent(predictions, MiniPatternRepeaterExpert.createMovementPrediction(context, info));
        addIfPresent(predictions, AlternatingMiniPatternRepeaterExpert.createMovementPrediction(context, info));
        addIfPresent(predictions, StopAimerExpert.createMovementPrediction(context));
        addIfPresent(predictions, EscapeAheadExpert.createMovementPrediction(context));
        addIfPresent(predictions, EscapeReverseExpert.createMovementPrediction(context));
        addIfPresent(predictions, LastDisplacementExpert.createMovementPrediction(context, info));
        addIfPresent(predictions, ReverseLastDisplacementExpert.createMovementPrediction(context, info));
        addIfPresent(predictions, HalfAheadExpert.createMovementPrediction(context));
        addIfPresent(predictions, HalfReverseExpert.createMovementPrediction(context));
        addIfPresent(predictions, CenterOfMassExpert.createMovementPrediction(context));
        return predictions;
    }

    static List<ExpertPrediction> createGunAllPredictions(WaveContextFeatures.WaveContext context,
                                                          EnemyInfo enemy,
                                                          Info info) {
        List<ExpertPrediction> predictions = createGunSourcePredictions(context, enemy, info);
        addIfPresent(predictions, CostanzaExpert.createGunPrediction(context, enemy, info));
        return predictions;
    }

    static List<ExpertPrediction> createMovementAllPredictions(WaveContextFeatures.WaveContext context,
                                                               Info info) {
        List<ExpertPrediction> predictions = createMovementSourcePredictions(context, info);
        addIfPresent(predictions, CostanzaExpert.createMovementPrediction(context, info));
        return predictions;
    }

    private static void addIfPresent(List<ExpertPrediction> predictions, ExpertPrediction prediction) {
        if (prediction != null && prediction.trajectory != null) {
            predictions.add(prediction);
        }
    }
}
