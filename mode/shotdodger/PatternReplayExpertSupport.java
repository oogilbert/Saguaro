package oog.mega.saguaro.mode.shotdodger;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

final class PatternReplayExpertSupport {
    interface MotionHistorySource {
        int size();

        double velocityAt(int index);

        double headingDeltaAt(int index);
    }

    private static final int MAX_SIMULATION_TICKS = 2000;

    private PatternReplayExpertSupport() {
    }

    static ExpertPrediction createPrediction(WaveContextFeatures.WaveContext context,
                                             double bandwidth,
                                             MotionHistorySource history,
                                             int requestedWindowTicks,
                                             boolean requireFullWindow,
                                             boolean reverseDirectionEachRepeat,
                                             String expertName) {
        validateContext(context, expertName);
        if (history == null || history.size() < 1) {
            return null;
        }
        if (requestedWindowTicks < 1) {
            return null;
        }
        if (requireFullWindow && history.size() < requestedWindowTicks) {
            return null;
        }
        int repeatWindowTicks = Math.min(requestedWindowTicks, history.size());
        PhysicsUtil.Trajectory targetTrajectory = simulateRepeatedPattern(
                context,
                history,
                repeatWindowTicks,
                reverseDirectionEachRepeat,
                expertName);
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
        GuessFactorDistribution distribution = new KDEDistribution(
                new double[]{clampedGf},
                bandwidth);
        return new ExpertPrediction(distribution, targetTrajectory, clampedGf);
    }

    private static PhysicsUtil.Trajectory simulateRepeatedPattern(WaveContextFeatures.WaveContext context,
                                                                  MotionHistorySource history,
                                                                  int repeatWindowTicks,
                                                                  boolean reverseDirectionEachRepeat,
                                                                  String expertName) {
        int patternStartIndex = history.size() - repeatWindowTicks;
        List<PhysicsUtil.PositionState> states = new ArrayList<PhysicsUtil.PositionState>();
        PhysicsUtil.PositionState current = new PhysicsUtil.PositionState(
                context.targetX,
                context.targetY,
                context.targetHeading,
                context.targetVelocity);
        states.add(current);
        for (int tick = 1; tick <= MAX_SIMULATION_TICKS; tick++) {
            int patternIndex = patternStartIndex + ((tick - 1) % repeatWindowTicks);
            int repeatIndex = (tick - 1) / repeatWindowTicks;
            double directionMultiplier = 1.0;
            if (reverseDirectionEachRepeat && (repeatIndex % 2 == 0)) {
                directionMultiplier = -1.0;
            }
            double nextHeading = MathUtils.normalizeAngle(
                    current.heading + directionMultiplier * history.headingDeltaAt(patternIndex));
            double nextVelocity = directionMultiplier * history.velocityAt(patternIndex);
            double nextX = clamp(
                    current.x + nextVelocity * FastTrig.sin(nextHeading),
                    PhysicsUtil.WALL_MARGIN,
                    context.battlefieldWidth - PhysicsUtil.WALL_MARGIN);
            double nextY = clamp(
                    current.y + nextVelocity * FastTrig.cos(nextHeading),
                    PhysicsUtil.WALL_MARGIN,
                    context.battlefieldHeight - PhysicsUtil.WALL_MARGIN);
            current = new PhysicsUtil.PositionState(nextX, nextY, nextHeading, nextVelocity);
            states.add(current);
            double innerRadius = context.bulletSpeed * (tick - 1);
            if (innerRadius > RobotHitbox.maxDistance(context.sourceX, context.sourceY, current.x, current.y)) {
                return new PhysicsUtil.Trajectory(states.toArray(new PhysicsUtil.PositionState[0]));
            }
        }
        throw new IllegalStateException(expertName + " simulation exceeded max tick budget");
    }

    private static void validateContext(WaveContextFeatures.WaveContext context, String expertName) {
        if (context == null) {
            throw new IllegalArgumentException(expertName + " requires a non-null context");
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
            throw new IllegalArgumentException(expertName + " requires finite fire-time geometry");
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
