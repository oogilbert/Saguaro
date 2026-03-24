package oog.mega.saguaro.movement;

import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;

/**
 * Shared collision detection and response utilities for ram simulation.
 * Used by both {@link RamSimulator} and the future-prediction opponent simulation
 * in {@link MovementEngine}.
 */
final class RamCollisionUtil {
    static final double ROBOT_HITBOX_SIZE = 36.0;

    private RamCollisionUtil() {
    }

    static boolean robotsOverlap(double x1, double y1, double x2, double y2) {
        return Math.abs(x1 - x2) < ROBOT_HITBOX_SIZE && Math.abs(y1 - y2) < ROBOT_HITBOX_SIZE;
    }

    static boolean isCollisionFault(double velocity,
                                    double heading,
                                    double x,
                                    double y,
                                    double otherX,
                                    double otherY) {
        if (velocity == 0.0) {
            return false;
        }
        double bearingToOther = Math.atan2(otherX - x, otherY - y);
        double relativeBearing = MathUtils.normalizeAngle(bearingToOther - heading);
        double halfPi = Math.PI / 2.0;
        if (velocity > 0.0) {
            return relativeBearing > -halfPi && relativeBearing < halfPi;
        }
        return relativeBearing < -halfPi || relativeBearing > halfPi;
    }

    static PhysicsUtil.PositionState rollbackCollision(PhysicsUtil.PositionState previous,
                                                       PhysicsUtil.PositionState collided) {
        return new PhysicsUtil.PositionState(
                previous.x,
                previous.y,
                collided.heading,
                0.0,
                collided.hitWall,
                collided.wallHitDamage);
    }

    static PhysicsUtil.PositionState advanceTowardPlannedState(PhysicsUtil.PositionState current,
                                                               PhysicsUtil.PositionState targetState,
                                                               boolean turnLocked,
                                                               double[] instructionBuffer,
                                                               double bfWidth,
                                                               double bfHeight) {
        PhysicsUtil.computeMovementInstructionInto(
                current.x, current.y, current.heading, current.velocity,
                targetState.x, targetState.y, instructionBuffer);
        if (turnLocked) {
            instructionBuffer[1] = 0.0;
        }
        return PhysicsUtil.calculateNextTick(
                current.x, current.y, current.heading, current.velocity,
                instructionBuffer[0], instructionBuffer[1],
                bfWidth, bfHeight);
    }
}
