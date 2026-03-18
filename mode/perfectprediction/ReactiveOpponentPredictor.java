package oog.mega.saguaro.mode.perfectprediction;

import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public interface ReactiveOpponentPredictor extends OpponentDriveSimulator.Instruction {
    ReactivePredictorId id();

    default PhysicsUtil.PositionState predictNextState(int tickOffset,
                                                       PhysicsUtil.PositionState ourFutureState,
                                                       PhysicsUtil.PositionState opponentState,
                                                       double battlefieldWidth,
                                                       double battlefieldHeight) {
        OpponentDriveSimulator.DriveTarget target = targetForTick(
                tickOffset,
                ourFutureState,
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
