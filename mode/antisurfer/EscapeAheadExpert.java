package oog.mega.saguaro.mode.antisurfer;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.KDEDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

final class EscapeAheadExpert {
    private static final int MAX_SIMULATION_TICKS = 2000;

    private EscapeAheadExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context) {
        return createGunPrediction(context).distribution;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context) {
        return createMovementPrediction(context).distribution;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context) {
        return createPredictionForOrbitDirection(
                context,
                BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH,
                resolveOrbitDirectionSign(context));
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context) {
        return createPredictionForOrbitDirection(
                context,
                BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH,
                resolveOrbitDirectionSign(context));
    }

    static ExpertPrediction createPredictionForOrbitDirection(WaveContextFeatures.WaveContext context,
                                                              double bandwidth,
                                                              int orbitDirectionSign) {
        RawPrediction rawPrediction = createRawPredictionForOrbitDirection(context, orbitDirectionSign);
        double clampedGf = AntiSurferPreciseMea.clampGf(context, rawPrediction.centerGf);
        GuessFactorDistribution distribution = new KDEDistribution(
                new double[]{clampedGf},
                bandwidth);
        return new ExpertPrediction(distribution, rawPrediction.trajectory, clampedGf);
    }

    static double[] preciseGfRange(WaveContextFeatures.WaveContext context) {
        validateContext(context);
        int aheadSign = resolveOrbitDirectionSign(context);
        double aheadGf = createRawPredictionForOrbitDirection(context, aheadSign).centerGf;
        double reverseGf = createRawPredictionForOrbitDirection(context, -aheadSign).centerGf;
        double minGf = Math.max(-1.0, Math.min(aheadGf, reverseGf));
        double maxGf = Math.min(1.0, Math.max(aheadGf, reverseGf));
        if (minGf > maxGf) {
            return new double[]{maxGf, minGf};
        }
        return new double[]{minGf, maxGf};
    }

    private static RawPrediction createRawPredictionForOrbitDirection(WaveContextFeatures.WaveContext context,
                                                                      int orbitDirectionSign) {
        validateContext(context);
        PhysicsUtil.Trajectory targetTrajectory = simulateEscapeOrbitTrajectory(context, orbitDirectionSign);
        OpponentDriveSimulator.AimSolution aimSolution = OpponentDriveSimulator.solveInterceptFromTrajectory(
                context.sourceX,
                context.sourceY,
                context.bulletSpeed,
                targetTrajectory);
        double referenceBearing = Math.atan2(
                context.targetX - context.sourceX,
                context.targetY - context.sourceY);
        double firingAngle = insideEdgeFiringAngle(context, aimSolution.interceptState, orbitDirectionSign);
        double meanGf = MathUtils.angleToGf(
                referenceBearing,
                firingAngle,
                MathUtils.maxEscapeAngle(context.bulletSpeed));
        return new RawPrediction(targetTrajectory, meanGf);
    }

    private static PhysicsUtil.Trajectory simulateEscapeOrbitTrajectory(WaveContextFeatures.WaveContext context,
                                                                        int orbitDirectionSign) {
        List<PhysicsUtil.PositionState> states = new ArrayList<PhysicsUtil.PositionState>();
        PhysicsUtil.PositionState current = new PhysicsUtil.PositionState(
                context.targetX,
                context.targetY,
                context.targetHeading,
                context.targetVelocity);
        states.add(current);
        PhysicsUtil.SteeringMode steeringMode = orbitDirectionSign > 0
                ? PhysicsUtil.SteeringMode.WALL_SMOOTHED_CW
                : PhysicsUtil.SteeringMode.WALL_SMOOTHED_CCW;
        double absoluteBearing = Math.atan2(
                current.x - context.sourceX,
                current.y - context.sourceY);
        double travelAngle = MathUtils.normalizeAngle(
                absoluteBearing + orbitDirectionSign * (Math.PI * 0.5));
        double maxDistance = maxDistanceInField(
                current.x,
                current.y,
                travelAngle,
                context.battlefieldWidth,
                context.battlefieldHeight);
        double targetX = clampToField(
                current.x + FastTrig.sin(travelAngle) * maxDistance,
                context.battlefieldWidth);
        double targetY = clampToField(
                current.y + FastTrig.cos(travelAngle) * maxDistance,
                context.battlefieldHeight);
        for (int tick = 1; tick <= MAX_SIMULATION_TICKS; tick++) {
            PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                    current,
                    targetX,
                    targetY,
                    1,
                    steeringMode,
                    context.battlefieldWidth,
                    context.battlefieldHeight);
            current = segment.stateAt(1);
            states.add(current);
            double innerRadius = context.bulletSpeed * (tick - 1);
            if (innerRadius > RobotHitbox.maxDistance(context.sourceX, context.sourceY, current.x, current.y)) {
                return new PhysicsUtil.Trajectory(states.toArray(new PhysicsUtil.PositionState[0]));
            }
        }
        throw new IllegalStateException("Escape-ahead simulation exceeded max tick budget");
    }

    private static void validateContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Escape ahead requires a non-null context");
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
            throw new IllegalArgumentException("Escape ahead requires finite fire-time geometry");
        }
    }

    private static double maxDistanceInField(double x,
                                             double y,
                                             double angle,
                                             double battlefieldWidth,
                                             double battlefieldHeight) {
        double minX = PhysicsUtil.WALL_MARGIN;
        double maxX = battlefieldWidth - PhysicsUtil.WALL_MARGIN;
        double minY = PhysicsUtil.WALL_MARGIN;
        double maxY = battlefieldHeight - PhysicsUtil.WALL_MARGIN;
        double dirX = FastTrig.sin(angle);
        double dirY = FastTrig.cos(angle);
        double tX = Double.POSITIVE_INFINITY;
        if (dirX > 1e-9) {
            tX = (maxX - x) / dirX;
        } else if (dirX < -1e-9) {
            tX = (minX - x) / dirX;
        }
        double tY = Double.POSITIVE_INFINITY;
        if (dirY > 1e-9) {
            tY = (maxY - y) / dirY;
        } else if (dirY < -1e-9) {
            tY = (minY - y) / dirY;
        }
        double maxDistance = Math.min(tX, tY);
        return Double.isFinite(maxDistance) ? Math.max(0.0, maxDistance) : 0.0;
    }

    private static double clampToField(double value, double fieldSize) {
        double margin = PhysicsUtil.WALL_MARGIN;
        return Math.max(margin, Math.min(fieldSize - margin, value));
    }

    private static double insideEdgeFiringAngle(WaveContextFeatures.WaveContext context,
                                                PhysicsUtil.PositionState interceptState,
                                                int orbitDirectionSign) {
        double interceptBearing = Math.atan2(
                interceptState.x - context.sourceX,
                interceptState.y - context.sourceY);
        double mea = MathUtils.maxEscapeAngle(context.bulletSpeed);
        double distance = Math.hypot(
                interceptState.x - context.sourceX,
                interceptState.y - context.sourceY);
        double[] intervalOffsets = RobotHitbox.guessFactorIntervalOffsets(
                interceptBearing,
                distance,
                mea);
        double insideOffset = orbitDirectionSign >= 0
                ? intervalOffsets[0]
                : intervalOffsets[1];
        return MathUtils.normalizeAngle(interceptBearing + insideOffset * mea);
    }

    static int resolveOrbitDirectionSign(WaveContextFeatures.WaveContext context) {
        return context.lateralDirectionSign >= 0 ? 1 : -1;
    }

    private static final class RawPrediction {
        final PhysicsUtil.Trajectory trajectory;
        final double centerGf;

        RawPrediction(PhysicsUtil.Trajectory trajectory, double centerGf) {
            this.trajectory = trajectory;
            this.centerGf = centerGf;
        }
    }
}
