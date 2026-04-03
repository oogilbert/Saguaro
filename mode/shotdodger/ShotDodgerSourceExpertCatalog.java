package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class ShotDodgerSourceExpertCatalog {
    private ShotDodgerSourceExpertCatalog() {
    }

    static ShotDodgerExpertSnapshot createMovementSnapshot(WaveContextFeatures.WaveContext context,
                                                           double averagedLinearLateralVelocity,
                                                           double averagedLinearNoAdjustLateralVelocity,
                                                           double linearConstantDivisor,
                                                           double linearConstantDivisorNoGunAdjust,
                                                           double latestHitBulletReturnHeading) {
        ShotDodgerExpertSnapshot snapshot = new ShotDodgerExpertSnapshot();
        snapshot.set(ShotDodgerExpertId.HEAD_ON, HeadOnExpert.createMovementPrediction(context));
        snapshot.set(ShotDodgerExpertId.LINEAR, LinearTargetingExpert.createMovementPrediction(context));
        snapshot.set(
                ShotDodgerExpertId.AVERAGED_LINEAR,
                AveragedLinearTargetingExpert.createMovementPrediction(context, averagedLinearLateralVelocity));
        snapshot.set(
                ShotDodgerExpertId.LINEAR_CONSTANT_DIVISOR,
                ConstantDivisorLinearExpert.createMovementPrediction(context, linearConstantDivisor));
        snapshot.set(ShotDodgerExpertId.CIRCULAR, CircularTargetingExpert.createMovementPrediction(context));
        snapshot.set(
                ShotDodgerExpertId.BATTLEFIELD_CENTER,
                BattlefieldCenterTargetingExpert.createMovementPrediction(context));
        snapshot.set(
                ShotDodgerExpertId.DROID_IMPACT_HEADING,
                DroidImpactHeadingExpert.createMovementPrediction(context, latestHitBulletReturnHeading));
        snapshot.set(
                ShotDodgerExpertId.AVERAGED_LINEAR_NO_GUN_ADJUST,
                AveragedLinearTargetingExpert.createMovementPrediction(context, averagedLinearNoAdjustLateralVelocity));
        snapshot.set(
                ShotDodgerExpertId.LINEAR_CONSTANT_DIVISOR_NO_GUN_ADJUST,
                ConstantDivisorLinearExpert.createMovementPrediction(context, linearConstantDivisorNoGunAdjust));
        return snapshot;
    }
}
