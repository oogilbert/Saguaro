package oog.mega.saguaro.mode.perfectprediction;

import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public final class RamPredictors {
    private static final double LEAD_DIVISOR = 8.0;

    private static final ReactiveOpponentPredictor DIRECT_PREDICTOR =
            predictor(ReactivePredictorId.RAM_DIRECT, false, false);
    private static final ReactiveOpponentPredictor DIRECT_BACK_AS_FRONT_PREDICTOR =
            predictor(ReactivePredictorId.RAM_DIRECT_BACK_AS_FRONT, false, true);
    private static final ReactiveOpponentPredictor LINEAR_PREDICTOR =
            predictor(ReactivePredictorId.RAM_LINEAR, true, false);
    private static final ReactiveOpponentPredictor LINEAR_BACK_AS_FRONT_PREDICTOR =
            predictor(ReactivePredictorId.RAM_LINEAR_BACK_AS_FRONT, true, true);

    private RamPredictors() {
    }

    public static ReactiveOpponentPredictor directPredictor() {
        return DIRECT_PREDICTOR;
    }

    public static ReactiveOpponentPredictor directBackAsFrontPredictor() {
        return DIRECT_BACK_AS_FRONT_PREDICTOR;
    }

    public static ReactiveOpponentPredictor linearPredictor() {
        return LINEAR_PREDICTOR;
    }

    public static ReactiveOpponentPredictor linearBackAsFrontPredictor() {
        return LINEAR_BACK_AS_FRONT_PREDICTOR;
    }

    private static ReactiveOpponentPredictor predictor(ReactivePredictorId predictorId,
                                                       boolean useLinearProjection,
                                                       boolean allowReverse) {
        return new ReactiveOpponentPredictor() {
            @Override
            public ReactivePredictorId id() {
                return predictorId;
            }

            @Override
            public OpponentDriveSimulator.DriveTarget targetForTick(int tickOffset,
                                                                    PhysicsUtil.PositionState ourFutureState,
                                                                    PhysicsUtil.PositionState opponentState,
                                                                    double battlefieldWidth,
                                                                    double battlefieldHeight) {
                if (!useLinearProjection) {
                    return new OpponentDriveSimulator.DriveTarget(ourFutureState.x, ourFutureState.y);
                }
                return linearProjectionTarget(ourFutureState, opponentState);
            }

            @Override
            public PhysicsUtil.PositionState predictNextState(int tickOffset,
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
                        allowReverse,
                        new double[2]);
            }
        };
    }

    private static OpponentDriveSimulator.DriveTarget linearProjectionTarget(PhysicsUtil.PositionState ourFutureState,
                                                                             PhysicsUtil.PositionState opponentState) {
        double absoluteBearing = Math.atan2(
                ourFutureState.x - opponentState.x,
                ourFutureState.y - opponentState.y);
        double lateralVelocity = ourFutureState.velocity * FastTrig.sin(ourFutureState.heading - absoluteBearing);
        double projectedBearing = MathUtils.normalizeAngle(absoluteBearing + lateralVelocity / LEAD_DIVISOR);
        double distance = Math.hypot(ourFutureState.x - opponentState.x, ourFutureState.y - opponentState.y);
        return new OpponentDriveSimulator.DriveTarget(
                opponentState.x + FastTrig.sin(projectedBearing) * distance,
                opponentState.y + FastTrig.cos(projectedBearing) * distance);
    }
}
