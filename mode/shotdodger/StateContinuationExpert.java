package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.ConstantDeltaPredictor;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

final class StateContinuationExpert {
    private StateContinuationExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return createGunPrediction(context).distribution;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return createMovementPrediction(context).distribution;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context) {
        return createPrediction(context, BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH);
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        return createPrediction(context, BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH);
    }

    private static ExpertPrediction createPrediction(WaveContextFeatures.WaveContext context,
                                                     double bandwidth) {
        validateContext(context);
        PhysicsUtil.PositionState targetStart = new PhysicsUtil.PositionState(
                context.targetX,
                context.targetY,
                context.targetHeading,
                context.targetVelocity);
        PhysicsUtil.Trajectory targetTrajectory = ConstantDeltaPredictor.simulateUntilWavePassed(
                context.sourceX,
                context.sourceY,
                context.bulletSpeed,
                targetStart,
                context.targetHeadingDelta,
                context.targetVelocityDelta,
                context.battlefieldWidth,
                context.battlefieldHeight);
        OpponentDriveSimulator.AimSolution aimSolution = OpponentDriveSimulator.solveInterceptFromTrajectory(
                context.sourceX,
                context.sourceY,
                context.bulletSpeed,
                targetTrajectory);
        double absoluteBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double meanGf = MathUtils.angleToGf(
                absoluteBearing,
                aimSolution.firingAngle,
                MathUtils.maxEscapeAngle(context.bulletSpeed));
        double clampedGf = ShotDodgerPreciseMea.clampGf(context, meanGf);
        return new ExpertPrediction(
                new KDEDistribution(new double[]{clampedGf}, bandwidth),
                targetTrajectory,
                clampedGf);
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("State-continuation expert requires a non-null context");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(context.targetHeading)
                || !Double.isFinite(context.targetVelocity)
                || !Double.isFinite(context.targetHeadingDelta)
                || !Double.isFinite(context.targetVelocityDelta)
                || !Double.isFinite(context.battlefieldWidth)
                || !Double.isFinite(context.battlefieldHeight)
                || !Double.isFinite(context.bulletSpeed)
                || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("State-continuation expert requires finite fire-time geometry");
        }
    }
}
