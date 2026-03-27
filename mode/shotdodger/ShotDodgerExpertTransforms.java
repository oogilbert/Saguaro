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
        double[] centers = new double[ShotDodgerExpertId.VALUES.length];
        Arrays.fill(centers, Double.NaN);
        for (ShotDodgerExpertId expertId : ShotDodgerExpertId.VALUES) {
            centers[expertId.ordinal()] = resolveCenterGf(snapshot, expertId, wave);
        }
        return centers;
    }

    private static double resolveCenterGf(ShotDodgerExpertSnapshot snapshot,
                                          ShotDodgerExpertId expertId,
                                          Wave wave) {
        ShotDodgerExpertId sourceExpertId = expertId.sourceExpertId();
        ExpertPrediction sourcePrediction = snapshot.get(sourceExpertId != null ? sourceExpertId : expertId);
        if (sourcePrediction == null || !Double.isFinite(sourcePrediction.centerGf)) {
            return Double.NaN;
        }
        if (!expertId.applyFireTimeBodyTurn()) {
            return sourcePrediction.centerGf;
        }
        return applyFireTimeBodyTurn(sourcePrediction.centerGf, wave);
    }

    private static double applyFireTimeBodyTurn(double baseCenterGf,
                                                Wave wave) {
        if (wave == null
                || wave.fireTimeContext == null
                || !Double.isFinite(wave.fireTimeShooterBodyTurn)) {
            return Double.NaN;
        }
        WaveContextFeatures.WaveContext context = wave.fireTimeContext;
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        if (!Double.isFinite(mea) || mea <= 0.0) {
            return Double.NaN;
        }
        double referenceBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double sourceAngle = MathUtils.gfToAngle(referenceBearing, baseCenterGf, mea);
        double adjustedAngle = MathUtils.normalizeAngle(sourceAngle + wave.fireTimeShooterBodyTurn);
        double adjustedGf = MathUtils.angleToGf(referenceBearing, adjustedAngle, mea);
        return Math.max(-1.0, Math.min(1.0, adjustedGf));
    }
}
