package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class ShotDodgerSourceExpertCatalog {
    private ShotDodgerSourceExpertCatalog() {
    }

    static ShotDodgerExpertSnapshot createGunSourceSnapshot(WaveContextFeatures.WaveContext context,
                                                            EnemyInfo enemy,
                                                            Info info) {
        ShotDodgerPreciseMea.range(context);
        ShotDodgerExpertSnapshot snapshot = new ShotDodgerExpertSnapshot();
        snapshot.set(ShotDodgerExpertId.STATE_CONTINUATION, StateContinuationExpert.createGunPrediction(context));
        snapshot.set(ShotDodgerExpertId.MINI_PATTERN, MiniPatternRepeaterExpert.createGunPrediction(context, enemy));
        snapshot.set(
                ShotDodgerExpertId.ALTERNATING_MINI_PATTERN,
                AlternatingMiniPatternRepeaterExpert.createGunPrediction(context, enemy));
        snapshot.set(ShotDodgerExpertId.STOP, StopAimerExpert.createGunPrediction(context));
        snapshot.set(ShotDodgerExpertId.ESCAPE_AHEAD, EscapeAheadExpert.createGunPrediction(context));
        snapshot.set(ShotDodgerExpertId.ESCAPE_REVERSE, EscapeReverseExpert.createGunPrediction(context));
        snapshot.set(ShotDodgerExpertId.LAST_DISPLACEMENT, LastDisplacementExpert.createGunPrediction(context, enemy));
        snapshot.set(
                ShotDodgerExpertId.REVERSE_LAST_DISPLACEMENT,
                ReverseLastDisplacementExpert.createGunPrediction(context, enemy));
        snapshot.set(ShotDodgerExpertId.HALF_AHEAD, HalfAheadExpert.createGunPrediction(context));
        snapshot.set(ShotDodgerExpertId.HALF_REVERSE, HalfReverseExpert.createGunPrediction(context));
        snapshot.set(ShotDodgerExpertId.CENTER_OF_MASS, CenterOfMassExpert.createGunPrediction(context));
        return snapshot;
    }

    static ShotDodgerExpertSnapshot createMovementSourceSnapshot(WaveContextFeatures.WaveContext context,
                                                                 Info info) {
        ShotDodgerPreciseMea.range(context);
        ShotDodgerExpertSnapshot snapshot = new ShotDodgerExpertSnapshot();
        snapshot.set(ShotDodgerExpertId.STATE_CONTINUATION, StateContinuationExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.MINI_PATTERN, MiniPatternRepeaterExpert.createMovementPrediction(context, info));
        snapshot.set(
                ShotDodgerExpertId.ALTERNATING_MINI_PATTERN,
                AlternatingMiniPatternRepeaterExpert.createMovementPrediction(context, info));
        snapshot.set(ShotDodgerExpertId.STOP, StopAimerExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.ESCAPE_AHEAD, EscapeAheadExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.ESCAPE_REVERSE, EscapeReverseExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.LAST_DISPLACEMENT, LastDisplacementExpert.createMovementPrediction(context, info));
        snapshot.set(
                ShotDodgerExpertId.REVERSE_LAST_DISPLACEMENT,
                ReverseLastDisplacementExpert.createMovementPrediction(context, info));
        snapshot.set(ShotDodgerExpertId.HALF_AHEAD, HalfAheadExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.HALF_REVERSE, HalfReverseExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.CENTER_OF_MASS, CenterOfMassExpert.createMovementPrediction(context));
        return snapshot;
    }

    static ShotDodgerExpertSnapshot createGunAllSnapshot(WaveContextFeatures.WaveContext context,
                                                         EnemyInfo enemy,
                                                         Info info) {
        ShotDodgerExpertSnapshot snapshot = createGunSourceSnapshot(context, enemy, info);
        snapshot.set(ShotDodgerExpertId.COSTANZA, CostanzaExpert.createGunPrediction(context, snapshot));
        return snapshot;
    }

    static ShotDodgerExpertSnapshot createMovementAllSnapshot(WaveContextFeatures.WaveContext context,
                                                              Info info) {
        ShotDodgerExpertSnapshot snapshot = createMovementSourceSnapshot(context, info);
        snapshot.set(ShotDodgerExpertId.COSTANZA, CostanzaExpert.createMovementPrediction(context, snapshot));
        return snapshot;
    }
}
