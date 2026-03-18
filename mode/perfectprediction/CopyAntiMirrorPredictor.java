package oog.mega.saguaro.mode.perfectprediction;

import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public final class CopyAntiMirrorPredictor {
    private static final double MAX_SPEED = 8.0;

    private static final ReactiveOpponentPredictor PREDICTOR = new ReactiveOpponentPredictor() {
        @Override
        public ReactivePredictorId id() {
            return ReactivePredictorId.ANTI_MIRROR_COPY;
        }

        @Override
        public OpponentDriveSimulator.DriveTarget targetForTick(int tickOffset,
                                                                PhysicsUtil.PositionState ourFutureState,
                                                                PhysicsUtil.PositionState opponentState,
                                                                double battlefieldWidth,
                                                                double battlefieldHeight) {
            MotionCommand command = motionCommand(ourFutureState, opponentState);
            return new OpponentDriveSimulator.DriveTarget(
                    opponentState.x + FastTrig.sin(command.heading) * MAX_SPEED,
                    opponentState.y + FastTrig.cos(command.heading) * MAX_SPEED);
        }

        @Override
        public PhysicsUtil.PositionState predictNextState(int tickOffset,
                                                          PhysicsUtil.PositionState ourFutureState,
                                                          PhysicsUtil.PositionState opponentState,
                                                          double battlefieldWidth,
                                                          double battlefieldHeight) {
            MotionCommand command = motionCommand(ourFutureState, opponentState);
            return PhysicsUtil.advanceTowardHeadingAndVelocity(
                    opponentState,
                    command.heading,
                    command.velocity,
                    battlefieldWidth,
                    battlefieldHeight);
        }
    };

    private CopyAntiMirrorPredictor() {
    }

    public static ReactiveOpponentPredictor predictor() {
        return PREDICTOR;
    }

    private static MotionCommand motionCommand(PhysicsUtil.PositionState ourFutureState,
                                               PhysicsUtil.PositionState opponentState) {
        if (ourFutureState == null || opponentState == null) {
            throw new IllegalArgumentException("Copy anti-mirror prediction requires non-null robot states");
        }
        return new MotionCommand(
                ourFutureState.heading,
                clamp(ourFutureState.velocity, -MAX_SPEED, MAX_SPEED));
    }

    private static double clamp(double value, double minValue, double maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private static final class MotionCommand {
        final double heading;
        final double velocity;

        MotionCommand(double heading, double velocity) {
            this.heading = heading;
            this.velocity = velocity;
        }
    }
}


