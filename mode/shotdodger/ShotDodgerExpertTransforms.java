package oog.mega.saguaro.mode.shotdodger;

import java.util.Arrays;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.MathUtils;

final class ShotDodgerExpertTransforms {
    private ShotDodgerExpertTransforms() {
    }

    static double[] createRenderGfMarkers(ShotDodgerExpertSnapshot snapshot,
                                          Wave wave) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        if (wave == null) {
            return snapshot.centers();
        }
        double[] centers = new double[ShotDodgerExpertId.VALUES.length];
        Arrays.fill(centers, Double.NaN);
        for (ShotDodgerExpertId expertId : ShotDodgerExpertId.VALUES) {
            centers[expertId.ordinal()] = resolveWaveCenterGf(snapshot, expertId, wave);
        }
        return centers;
    }

    private static double resolveWaveCenterGf(ShotDodgerExpertSnapshot snapshot,
                                              ShotDodgerExpertId expertId,
                                              Wave wave) {
        double sourceReferenceBearing = sourceReferenceBearing(wave);
        double sourceMea = sourceMea(wave);
        double referenceBearing = fireTimeReferenceBearing(wave);
        double mea = fireTimeMea(wave);
        if (!Double.isFinite(sourceReferenceBearing)
                || !Double.isFinite(sourceMea)
                || sourceMea <= 0.0
                || !Double.isFinite(referenceBearing)
                || !Double.isFinite(mea)
                || mea <= 0.0) {
            return Double.NaN;
        }
        double absoluteAngle = resolveAbsoluteAngle(snapshot, expertId, wave, sourceReferenceBearing, sourceMea);
        if (!Double.isFinite(absoluteAngle)) {
            return Double.NaN;
        }
        double gf = MathUtils.angleToGf(referenceBearing, absoluteAngle, mea);
        return Math.max(-1.0, Math.min(1.0, gf));
    }

    private static double resolveAbsoluteAngle(ShotDodgerExpertSnapshot snapshot,
                                               ShotDodgerExpertId expertId,
                                               Wave wave,
                                               double sourceReferenceBearing,
                                               double sourceMea) {
        ShotDodgerExpertId sourceExpertId = expertId.sourceExpertId() != null
                ? expertId.sourceExpertId()
                : expertId;
        double absoluteAngle = resolveSourceAbsoluteAngle(snapshot, sourceExpertId, sourceReferenceBearing, sourceMea);
        if (!Double.isFinite(absoluteAngle)) {
            return Double.NaN;
        }
        if (expertId.applyFireTimeBodyTurn()) {
            if (!Double.isFinite(wave.fireTimeShooterBodyTurn)) {
                return Double.NaN;
            }
            absoluteAngle = MathUtils.normalizeAngle(absoluteAngle + wave.fireTimeShooterBodyTurn);
        }
        return absoluteAngle;
    }

    private static double resolveSourceAbsoluteAngle(ShotDodgerExpertSnapshot snapshot,
                                                     ShotDodgerExpertId sourceExpertId,
                                                     double sourceReferenceBearing,
                                                     double sourceMea) {
        ExpertPrediction sourcePrediction = snapshot.get(sourceExpertId);
        if (sourcePrediction == null || !Double.isFinite(sourcePrediction.centerGf)) {
            return Double.NaN;
        }
        return MathUtils.gfToAngle(sourceReferenceBearing, sourcePrediction.centerGf, sourceMea);
    }

    private static double sourceReferenceBearing(Wave wave) {
        if (wave == null
                || !Double.isFinite(wave.priorTickShooterX)
                || !Double.isFinite(wave.priorTickShooterY)
                || !Double.isFinite(wave.priorTickTargetX)
                || !Double.isFinite(wave.priorTickTargetY)) {
            return fireTimeReferenceBearing(wave);
        }
        return Math.atan2(
                wave.priorTickTargetX - wave.priorTickShooterX,
                wave.priorTickTargetY - wave.priorTickShooterY);
    }

    private static double sourceMea(Wave wave) {
        return fireTimeMea(wave);
    }

    private static double fireTimeReferenceBearing(Wave wave) {
        if (wave == null) {
            return Double.NaN;
        }
        if (Double.isFinite(wave.targetX)
                && Double.isFinite(wave.targetY)
                && Double.isFinite(wave.originX)
                && Double.isFinite(wave.originY)) {
            return Math.atan2(wave.targetX - wave.originX, wave.targetY - wave.originY);
        }
        if (wave.fireTimeContext == null) {
            return Double.NaN;
        }
        WaveContextFeatures.WaveContext context = wave.fireTimeContext;
        return Math.atan2(context.targetX - context.sourceX, context.targetY - context.sourceY);
    }

    private static double fireTimeMea(Wave wave) {
        if (wave == null) {
            return Double.NaN;
        }
        if (Double.isFinite(wave.speed) && wave.speed > 0.0) {
            return MathUtils.maxEscapeAngle(wave.speed);
        }
        if (wave.fireTimeContext == null || !Double.isFinite(wave.fireTimeContext.bulletSpeed)) {
            return Double.NaN;
        }
        return MathUtils.maxEscapeAngle(wave.fireTimeContext.bulletSpeed);
    }
}
