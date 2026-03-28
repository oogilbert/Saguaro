package oog.mega.saguaro.mode.perfectprediction;

import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public final class LearnedPatternPredictor implements ReactiveOpponentPredictor {
    private final double[] turnDeltas;
    private final double[] targetVelocities;
    private final double fallbackTurnDelta;
    private final double fallbackVelocity;

    private LearnedPatternPredictor(double[] turnDeltas,
                                    double[] targetVelocities,
                                    double fallbackTurnDelta,
                                    double fallbackVelocity) {
        if (turnDeltas == null || targetVelocities == null) {
            throw new IllegalArgumentException("Learned-pattern predictor requires non-null motion arrays");
        }
        if (turnDeltas.length != targetVelocities.length) {
            throw new IllegalArgumentException("Learned-pattern predictor requires aligned turn/velocity arrays");
        }
        this.turnDeltas = turnDeltas.clone();
        this.targetVelocities = targetVelocities.clone();
        this.fallbackTurnDelta = fallbackTurnDelta;
        this.fallbackVelocity = fallbackVelocity;
    }

    public static LearnedPatternPredictor repeating(double fallbackTurnDelta, double fallbackVelocity) {
        return new LearnedPatternPredictor(new double[0], new double[0], fallbackTurnDelta, fallbackVelocity);
    }

    public static LearnedPatternPredictor fromScript(double[] turnDeltas,
                                                     double[] targetVelocities,
                                                     double fallbackTurnDelta,
                                                     double fallbackVelocity) {
        return new LearnedPatternPredictor(turnDeltas, targetVelocities, fallbackTurnDelta, fallbackVelocity);
    }

    @Override
    public ReactivePredictorId id() {
        return ReactivePredictorId.LEARNED_PATTERN;
    }

    @Override
    public PhysicsUtil.PositionState predictNextState(int tickOffset,
                                                      PhysicsUtil.PositionState ourFutureState,
                                                      PhysicsUtil.PositionState opponentState,
                                                      double battlefieldWidth,
                                                      double battlefieldHeight) {
        if (opponentState == null) {
            throw new IllegalArgumentException("Learned-pattern prediction requires a non-null opponent state");
        }
        double turnDelta = scriptedTurnDelta(tickOffset);
        double targetVelocity = scriptedVelocity(tickOffset);
        return PhysicsUtil.advanceTowardHeadingAndVelocity(
                opponentState,
                opponentState.heading + turnDelta,
                targetVelocity,
                battlefieldWidth,
                battlefieldHeight);
    }

    @Override
    public OpponentDriveSimulator.DriveTarget targetForTick(int tickOffset,
                                                            PhysicsUtil.PositionState ourFutureState,
                                                            PhysicsUtil.PositionState opponentState,
                                                            double battlefieldWidth,
                                                            double battlefieldHeight) {
        PhysicsUtil.PositionState nextState = predictNextState(
                tickOffset,
                ourFutureState,
                opponentState,
                battlefieldWidth,
                battlefieldHeight);
        return new OpponentDriveSimulator.DriveTarget(nextState.x, nextState.y);
    }

    private double scriptedTurnDelta(int tickOffset) {
        if (turnDeltas.length == 0) {
            return fallbackTurnDelta;
        }
        int index = Math.max(0, Math.min(tickOffset, turnDeltas.length - 1));
        return turnDeltas[index];
    }

    private double scriptedVelocity(int tickOffset) {
        if (targetVelocities.length == 0) {
            return fallbackVelocity;
        }
        int index = Math.max(0, Math.min(tickOffset, targetVelocities.length - 1));
        return targetVelocities[index];
    }
}
