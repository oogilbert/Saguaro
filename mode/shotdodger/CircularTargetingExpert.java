package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;

final class CircularTargetingExpert {
    private static final double WALL_MARGIN = 18.0;

    private CircularTargetingExpert() {
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        validateContext(context);

        double absoluteBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double predictedX = context.targetX;
        double predictedY = context.targetY;
        double predictedHeading = context.targetHeading;
        double predictedVelocity = context.targetVelocity;
        double predictedHeadingChange = context.targetHeadingDelta;
        int deltaTime = 0;

        while ((++deltaTime) * context.bulletSpeed
                < Math.hypot(predictedX - context.sourceX, predictedY - context.sourceY)) {
            predictedX += Math.sin(predictedHeading) * predictedVelocity;
            predictedY += Math.cos(predictedHeading) * predictedVelocity;
            predictedHeading = MathUtils.normalizeAngle(predictedHeading + predictedHeadingChange);

            if (predictedX < WALL_MARGIN
                    || predictedY < WALL_MARGIN
                    || predictedX > context.battlefieldWidth - WALL_MARGIN
                    || predictedY > context.battlefieldHeight - WALL_MARGIN) {
                predictedX = clamp(predictedX, WALL_MARGIN, context.battlefieldWidth - WALL_MARGIN);
                predictedY = clamp(predictedY, WALL_MARGIN, context.battlefieldHeight - WALL_MARGIN);
                break;
            }
        }

        double predictedAngle = Math.atan2(
                predictedX - context.sourceX,
                predictedY - context.sourceY);
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        double centerGf = mea > 0.0
                ? MathUtils.angleToGf(absoluteBearing, predictedAngle, mea)
                : 0.0;
        centerGf = clamp(centerGf, -1.0, 1.0);
        return new ExpertPrediction(
                new KDEDistribution(new double[] { centerGf }, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH),
                null,
                centerGf);
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Circular-targeting expert requires a non-null context");
        }
        if (!Double.isFinite(context.bulletSpeed) || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Circular-targeting expert requires a positive bullet speed");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(context.targetHeading)
                || !Double.isFinite(context.targetVelocity)
                || !Double.isFinite(context.targetHeadingDelta)
                || !Double.isFinite(context.battlefieldWidth)
                || !Double.isFinite(context.battlefieldHeight)) {
            throw new IllegalArgumentException("Circular-targeting expert requires finite context state");
        }
    }

    private static double clamp(double value,
                                double minValue,
                                double maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }
}
