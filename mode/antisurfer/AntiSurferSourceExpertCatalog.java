package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class AntiSurferSourceExpertCatalog {
    private AntiSurferSourceExpertCatalog() {
    }

    static AntiSurferExpertSnapshot createGunSourceSnapshot(WaveContextFeatures.WaveContext context,
                                                            EnemyInfo enemy,
                                                            Info info) {
        AntiSurferPreciseMea.range(context);
        AntiSurferExpertSnapshot snapshot = new AntiSurferExpertSnapshot();
        snapshot.set(AntiSurferExpertId.STATE_CONTINUATION, StateContinuationExpert.createGunPrediction(context));
        snapshot.set(AntiSurferExpertId.MINI_PATTERN, MiniPatternRepeaterExpert.createGunPrediction(context, enemy));
        snapshot.set(
                AntiSurferExpertId.ALTERNATING_MINI_PATTERN,
                AlternatingMiniPatternRepeaterExpert.createGunPrediction(context, enemy));
        snapshot.set(AntiSurferExpertId.STOP, StopAimerExpert.createGunPrediction(context));
        snapshot.set(AntiSurferExpertId.ESCAPE_AHEAD, EscapeAheadExpert.createGunPrediction(context));
        snapshot.set(AntiSurferExpertId.ESCAPE_REVERSE, EscapeReverseExpert.createGunPrediction(context));
        snapshot.set(AntiSurferExpertId.LAST_DISPLACEMENT, LastDisplacementExpert.createGunPrediction(context, enemy));
        snapshot.set(
                AntiSurferExpertId.REVERSE_LAST_DISPLACEMENT,
                ReverseLastDisplacementExpert.createGunPrediction(context, enemy));
        snapshot.set(AntiSurferExpertId.HALF_AHEAD, HalfAheadExpert.createGunPrediction(context));
        snapshot.set(AntiSurferExpertId.HALF_REVERSE, HalfReverseExpert.createGunPrediction(context));
        snapshot.set(AntiSurferExpertId.CENTER_OF_MASS, CenterOfMassExpert.createGunPrediction(context));
        return snapshot;
    }

    static AntiSurferExpertSnapshot createMovementSourceSnapshot(WaveContextFeatures.WaveContext context,
                                                                 Info info) {
        AntiSurferPreciseMea.range(context);
        AntiSurferExpertSnapshot snapshot = new AntiSurferExpertSnapshot();
        snapshot.set(AntiSurferExpertId.STATE_CONTINUATION, StateContinuationExpert.createMovementPrediction(context));
        snapshot.set(AntiSurferExpertId.MINI_PATTERN, MiniPatternRepeaterExpert.createMovementPrediction(context, info));
        snapshot.set(
                AntiSurferExpertId.ALTERNATING_MINI_PATTERN,
                AlternatingMiniPatternRepeaterExpert.createMovementPrediction(context, info));
        snapshot.set(AntiSurferExpertId.STOP, StopAimerExpert.createMovementPrediction(context));
        snapshot.set(AntiSurferExpertId.ESCAPE_AHEAD, EscapeAheadExpert.createMovementPrediction(context));
        snapshot.set(AntiSurferExpertId.ESCAPE_REVERSE, EscapeReverseExpert.createMovementPrediction(context));
        snapshot.set(AntiSurferExpertId.LAST_DISPLACEMENT, LastDisplacementExpert.createMovementPrediction(context, info));
        snapshot.set(
                AntiSurferExpertId.REVERSE_LAST_DISPLACEMENT,
                ReverseLastDisplacementExpert.createMovementPrediction(context, info));
        snapshot.set(AntiSurferExpertId.HALF_AHEAD, HalfAheadExpert.createMovementPrediction(context));
        snapshot.set(AntiSurferExpertId.HALF_REVERSE, HalfReverseExpert.createMovementPrediction(context));
        snapshot.set(AntiSurferExpertId.CENTER_OF_MASS, CenterOfMassExpert.createMovementPrediction(context));
        return snapshot;
    }

    static AntiSurferExpertSnapshot createGunAllSnapshot(WaveContextFeatures.WaveContext context,
                                                         EnemyInfo enemy,
                                                         Info info) {
        AntiSurferExpertSnapshot snapshot = createGunSourceSnapshot(context, enemy, info);
        snapshot.set(AntiSurferExpertId.COSTANZA, CostanzaExpert.createGunPrediction(context, snapshot));
        return snapshot;
    }

    static AntiSurferExpertSnapshot createMovementAllSnapshot(WaveContextFeatures.WaveContext context,
                                                              Info info) {
        AntiSurferExpertSnapshot snapshot = createMovementSourceSnapshot(context, info);
        snapshot.set(AntiSurferExpertId.COSTANZA, CostanzaExpert.createMovementPrediction(context, snapshot));
        return snapshot;
    }
}
