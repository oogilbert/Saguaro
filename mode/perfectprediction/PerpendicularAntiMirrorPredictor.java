package oog.mega.saguaro.mode.perfectprediction;

import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public final class PerpendicularAntiMirrorPredictor {
    private static final double MAX_SPEED = 8.0;

    private static final ReactiveOpponentPredictor PREDICTOR = new ReactiveOpponentPredictor() {
        @Override
        public ReactivePredictorId id() {
            return ReactivePredictorId.ANTI_MIRROR_PERPENDICULAR;
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

    private PerpendicularAntiMirrorPredictor() {
    }

    public static ReactiveOpponentPredictor predictor() {
        return PREDICTOR;
    }

    private static MotionCommand motionCommand(PhysicsUtil.PositionState ourFutureState,
                                               PhysicsUtil.PositionState opponentState) {
        if (ourFutureState == null || opponentState == null) {
            throw new IllegalArgumentException(
                    "Perpendicular anti-mirror prediction requires non-null robot states");
        }
        double absoluteBearing = Math.atan2(
                ourFutureState.x - opponentState.x,
                ourFutureState.y - opponentState.y);
        double lateralVelocity = ourFutureState.velocity * FastTrig.sin(ourFutureState.heading - absoluteBearing);
        return new MotionCommand(
                MathUtils.normalizeAngle(absoluteBearing + Math.PI * 0.5),
                clamp(lateralVelocity, -MAX_SPEED, MAX_SPEED));
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


