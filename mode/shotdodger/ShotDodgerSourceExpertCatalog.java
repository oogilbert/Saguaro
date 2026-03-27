package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class ShotDodgerSourceExpertCatalog {
    private ShotDodgerSourceExpertCatalog() {
    }

    static ShotDodgerExpertSnapshot createMovementSnapshot(WaveContextFeatures.WaveContext context) {
        ShotDodgerExpertSnapshot snapshot = new ShotDodgerExpertSnapshot();
        snapshot.set(ShotDodgerExpertId.HEAD_ON, HeadOnExpert.createMovementPrediction(context));
        return snapshot;
    }
}
