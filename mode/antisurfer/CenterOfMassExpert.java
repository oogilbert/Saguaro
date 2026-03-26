package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

final class CenterOfMassExpert {
    private static final double MIN_WEIGHT = 1e-9;

    private CenterOfMassExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return createGunPrediction(context).distribution;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return createMovementPrediction(context).distribution;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context) {
        return createPrediction(
                context,
                EscapeAheadExpert.createGunPrediction(context),
                EscapeReverseExpert.createGunPrediction(context),
                BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH);
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        return createPrediction(
                context,
                EscapeAheadExpert.createMovementPrediction(context),
                EscapeReverseExpert.createMovementPrediction(context),
                BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH);
    }

    private static ExpertPrediction createPrediction(WaveContextFeatures.WaveContext context,
                                                     ExpertPrediction aheadPrediction,
                                                     ExpertPrediction reversePrediction,
                                                     double bandwidth) {
        if (aheadPrediction == null) {
            return reversePrediction;
        }
        if (reversePrediction == null) {
            return aheadPrediction;
        }

        OpponentDriveSimulator.AimSolution aheadAim = OpponentDriveSimulator.solveInterceptFromTrajectory(
                context.sourceX,
                context.sourceY,
                context.bulletSpeed,
                aheadPrediction.trajectory);
        OpponentDriveSimulator.AimSolution reverseAim = OpponentDriveSimulator.solveInterceptFromTrajectory(
                context.sourceX,
                context.sourceY,
                context.bulletSpeed,
                reversePrediction.trajectory);

        double referenceBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);

        double aheadCenterBearing = Math.atan2(
                aheadAim.interceptState.x - context.sourceX,
                aheadAim.interceptState.y - context.sourceY);
        double reverseCenterBearing = Math.atan2(
                reverseAim.interceptState.x - context.sourceX,
                reverseAim.interceptState.y - context.sourceY);
        double aheadCenterGf = MathUtils.angleToGf(referenceBearing, aheadCenterBearing, mea);
        double reverseCenterGf = MathUtils.angleToGf(referenceBearing, reverseCenterBearing, mea);

        double aheadWeight = angularWidth(context, aheadAim.interceptState);
        double reverseWeight = angularWidth(context, reverseAim.interceptState);
        double totalWeight = aheadWeight + reverseWeight;
        if (totalWeight <= MIN_WEIGHT) {
            totalWeight = 2.0 * MIN_WEIGHT;
            aheadWeight = MIN_WEIGHT;
            reverseWeight = MIN_WEIGHT;
        }

        double centerGf = (aheadWeight * aheadCenterGf + reverseWeight * reverseCenterGf) / totalWeight;
        double clampedGf = AntiSurferPreciseMea.clampGf(context, centerGf);
        GuessFactorDistribution distribution = new KDEDistribution(
                new double[]{clampedGf},
                bandwidth);
        PhysicsUtil.Trajectory blendedTrajectory = blendTrajectories(
                aheadPrediction.trajectory,
                reversePrediction.trajectory,
                aheadWeight,
                reverseWeight);
        return new ExpertPrediction(distribution, blendedTrajectory, clampedGf);
    }

    private static double angularWidth(WaveContextFeatures.WaveContext context,
                                       PhysicsUtil.PositionState interceptState) {
        double[] interval = RobotHitbox.angularInterval(
                context.sourceX,
                context.sourceY,
                interceptState.x,
                interceptState.y);
        if (interval == null) {
            return MIN_WEIGHT;
        }
        return Math.max(MIN_WEIGHT, interval[1] - interval[0]);
    }

    private static PhysicsUtil.Trajectory blendTrajectories(PhysicsUtil.Trajectory aheadTrajectory,
                                                            PhysicsUtil.Trajectory reverseTrajectory,
                                                            double aheadWeight,
                                                            double reverseWeight) {
        double totalWeight = aheadWeight + reverseWeight;
        double normalizedAhead = totalWeight > MIN_WEIGHT ? aheadWeight / totalWeight : 0.5;
        double normalizedReverse = totalWeight > MIN_WEIGHT ? reverseWeight / totalWeight : 0.5;
        int maxLength = Math.max(aheadTrajectory.length(), reverseTrajectory.length());
        PhysicsUtil.PositionState[] states = new PhysicsUtil.PositionState[maxLength];
        for (int i = 0; i < maxLength; i++) {
            PhysicsUtil.PositionState aheadState = aheadTrajectory.stateAt(i);
            PhysicsUtil.PositionState reverseState = reverseTrajectory.stateAt(i);
            states[i] = new PhysicsUtil.PositionState(
                    normalizedAhead * aheadState.x + normalizedReverse * reverseState.x,
                    normalizedAhead * aheadState.y + normalizedReverse * reverseState.y,
                    interpolateAngle(aheadState.heading, reverseState.heading, normalizedReverse),
                    normalizedAhead * aheadState.velocity + normalizedReverse * reverseState.velocity);
        }
        return new PhysicsUtil.Trajectory(states);
    }

    private static double interpolateAngle(double startAngle,
                                           double endAngle,
                                           double endWeight) {
        return MathUtils.normalizeAngle(
                startAngle + MathUtils.normalizeAngle(endAngle - startAngle) * endWeight);
    }
}
