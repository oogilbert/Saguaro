package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class ShotDodgerSourceExpertCatalog {
    private ShotDodgerSourceExpertCatalog() {
    }

    static ShotDodgerExpertSnapshot createMovementSnapshot(WaveContextFeatures.WaveContext context,
                                                           double linearConstantDivisor,
                                                           double linearConstantDivisorNoGunAdjust) {
        ShotDodgerExpertSnapshot snapshot = new ShotDodgerExpertSnapshot();
        snapshot.set(ShotDodgerExpertId.HEAD_ON, HeadOnExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.LINEAR, LinearTargetingExpert.createMovementPrediction(context));
        snapshot.set(
                ShotDodgerExpertId.LINEAR_CONSTANT_DIVISOR,
                ConstantDivisorLinearExpert.createMovementPrediction(context, linearConstantDivisor));
        snapshot.set(
                ShotDodgerExpertId.LINEAR_CONSTANT_DIVISOR_NO_GUN_ADJUST,
                ConstantDivisorLinearExpert.createMovementPrediction(context, linearConstantDivisorNoGunAdjust));
        return snapshot;
    }
}
