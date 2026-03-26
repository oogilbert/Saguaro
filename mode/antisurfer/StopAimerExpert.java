package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

final class StopAimerExpert {
    private static final int MAX_SIMULATION_TICKS = 2000;

    private StopAimerExpert() {
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
        PhysicsUtil.Trajectory targetTrajectory = simulateStopTrajectoryUntilWavePassed(context);
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
        double clampedGf = AntiSurferPreciseMea.clampGf(context, meanGf);
        return new ExpertPrediction(
                new KDEDistribution(new double[]{clampedGf}, bandwidth),
                targetTrajectory,
                clampedGf);
    }

    private static PhysicsUtil.Trajectory simulateStopTrajectoryUntilWavePassed(
            WaveContextFeatures.WaveContext context) {
        List<PhysicsUtil.PositionState> states = new ArrayList<PhysicsUtil.PositionState>();
        PhysicsUtil.PositionState current = new PhysicsUtil.PositionState(
                context.targetX,
                context.targetY,
                context.targetHeading,
                context.targetVelocity);
        states.add(current);
        for (int tick = 1; tick <= MAX_SIMULATION_TICKS; tick++) {
            current = PhysicsUtil.calculateNextTick(
                    current.x,
                    current.y,
                    current.heading,
                    current.velocity,
                    0.0,
                    0.0,
                    context.battlefieldWidth,
                    context.battlefieldHeight);
            states.add(current);
            double innerRadius = context.bulletSpeed * (tick - 1);
            if (innerRadius > RobotHitbox.maxDistance(context.sourceX, context.sourceY, current.x, current.y)) {
                return new PhysicsUtil.Trajectory(states.toArray(new PhysicsUtil.PositionState[0]));
            }
        }
        throw new IllegalStateException("Stop-aimer simulation exceeded max tick budget");
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Stop aimer requires a non-null context");
        }
        if (!Double.isFinite(context.sourceX)
                || !Double.isFinite(context.sourceY)
                || !Double.isFinite(context.targetX)
                || !Double.isFinite(context.targetY)
                || !Double.isFinite(context.targetHeading)
                || !Double.isFinite(context.targetVelocity)
                || !Double.isFinite(context.battlefieldWidth)
                || !Double.isFinite(context.battlefieldHeight)
                || !Double.isFinite(context.bulletSpeed)
                || context.bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Stop aimer requires finite fire-time geometry");
        }
    }
}
