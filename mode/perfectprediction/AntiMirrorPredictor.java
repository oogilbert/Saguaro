package oog.mega.saguaro.mode.perfectprediction;

import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public final class AntiMirrorPredictor {
    private static final ReactiveOpponentPredictor REFLECTED_POINT_PREDICTOR =
            new ReactiveOpponentPredictor() {
                @Override
                public ReactivePredictorId id() {
                    return ReactivePredictorId.ANTI_MIRROR;
                }

                @Override
                public OpponentDriveSimulator.DriveTarget targetForTick(int tickOffset,
                                                                        PhysicsUtil.PositionState ourFutureState,
                                                                        PhysicsUtil.PositionState opponentState,
                                                                        double battlefieldWidth,
                                                                        double battlefieldHeight) {
                    return new OpponentDriveSimulator.DriveTarget(
                            battlefieldWidth - ourFutureState.x,
                            battlefieldHeight - ourFutureState.y);
                }
            };

    private AntiMirrorPredictor() {
    }

    public static ReactiveOpponentPredictor predictor() {
        return REFLECTED_POINT_PREDICTOR;
    }

    public static OpponentDriveSimulator.Instruction reflectedPointInstruction() {
        return REFLECTED_POINT_PREDICTOR;
    }

    public static PhysicsUtil.PositionState predictNextTick(PhysicsUtil.PositionState ourState,
                                                            PhysicsUtil.PositionState opponentState,
                                                            double battlefieldWidth,
                                                            double battlefieldHeight) {
        if (ourState == null || opponentState == null) {
            throw new IllegalArgumentException("Anti-mirror prediction requires non-null robot states");
        }
        OpponentDriveSimulator.DriveTarget target = REFLECTED_POINT_PREDICTOR.targetForTick(
                0,
                ourState,
                opponentState,
                battlefieldWidth,
                battlefieldHeight);
        return PhysicsUtil.advanceTowardTarget(
                opponentState,
                target.x,
                target.y,
                battlefieldWidth,
                battlefieldHeight,
                new double[2]);
    }
}
