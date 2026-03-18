package oog.mega.saguaro.mode.scripted;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import robocode.Rules;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.perfectprediction.ReactiveOpponentPredictor;

public final class OpponentDriveSimulator {
    public interface Instruction {
        DriveTarget targetForTick(int tickOffset,
                                  PhysicsUtil.PositionState ourFutureState,
                                  PhysicsUtil.PositionState opponentState,
                                  double battlefieldWidth,
                                  double battlefieldHeight);
    }

    public static final class DriveTarget {
        public final double x;
        public final double y;

        public DriveTarget(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Drive target requires finite coordinates");
            }
            this.x = x;
            this.y = y;
        }
    }

    public static final class AimSolution {
        public final PhysicsUtil.PositionState interceptState;
        public final double firingAngle;
        public final int interceptTick;

        public AimSolution(PhysicsUtil.PositionState interceptState, double firingAngle, int interceptTick) {
            if (interceptState == null) {
                throw new IllegalArgumentException("Aim solution requires a non-null intercept state");
            }
            if (!Double.isFinite(firingAngle)) {
                throw new IllegalArgumentException("Aim solution requires a finite firing angle");
            }
            if (interceptTick < 1) {
                throw new IllegalArgumentException("Aim solution requires a positive intercept tick");
            }
            this.interceptState = interceptState;
            this.firingAngle = firingAngle;
            this.interceptTick = interceptTick;
        }
    }

    private OpponentDriveSimulator() {
    }

    public static PhysicsUtil.Trajectory simulateTrajectory(PhysicsUtil.PositionState opponentStart,
                                                            PhysicsUtil.Trajectory ourTrajectory,
                                                            Instruction instruction,
                                                            int ticks,
                                                            double battlefieldWidth,
                                                            double battlefieldHeight) {
        if (opponentStart == null) {
            throw new IllegalArgumentException("Opponent simulation requires a non-null start state");
        }
        if (ourTrajectory == null) {
            throw new IllegalArgumentException("Opponent simulation requires a non-null friendly trajectory");
        }
        if (instruction == null) {
            throw new IllegalArgumentException("Opponent simulation requires a non-null instruction");
        }
        if (ticks < 0) {
            throw new IllegalArgumentException("Opponent simulation tick count must be non-negative");
        }

        PhysicsUtil.PositionState[] states = new PhysicsUtil.PositionState[ticks + 1];
        states[0] = opponentStart;
        PhysicsUtil.PositionState current = opponentStart;
        double[] movementInstruction = new double[2];
        for (int tick = 0; tick < ticks; tick++) {
            if (instruction instanceof ReactiveOpponentPredictor) {
                current = ((ReactiveOpponentPredictor) instruction).predictNextState(
                        tick,
                        ourTrajectory.stateAt(tick),
                        current,
                        battlefieldWidth,
                        battlefieldHeight);
            } else {
                DriveTarget target = instruction.targetForTick(
                        tick,
                        ourTrajectory.stateAt(tick),
                        current,
                        battlefieldWidth,
                        battlefieldHeight);
                if (target == null) {
                    throw new IllegalStateException("Opponent instruction returned null target at tick " + tick);
                }
                current = PhysicsUtil.advanceTowardTarget(
                        current,
                        target.x,
                        target.y,
                        battlefieldWidth,
                        battlefieldHeight,
                        movementInstruction);
            }
            states[tick + 1] = current;
        }
        return new PhysicsUtil.Trajectory(states);
    }

    public static AimSolution solveIntercept(double shooterX,
                                             double shooterY,
                                             double bulletSpeed,
                                             PhysicsUtil.PositionState opponentStart,
                                             PhysicsUtil.Trajectory ourTrajectory,
                                             Instruction instruction,
                                             int maxTicks,
                                             double battlefieldWidth,
                                             double battlefieldHeight) {
        if (maxTicks < 1) {
            throw new IllegalArgumentException("Aim solve requires at least one simulated tick");
        }

        PhysicsUtil.Trajectory opponentTrajectory = simulateTrajectory(
                opponentStart,
                ourTrajectory,
                instruction,
                maxTicks,
                battlefieldWidth,
                battlefieldHeight);
        return solveInterceptFromTrajectory(shooterX, shooterY, bulletSpeed, opponentTrajectory);
    }

    public static AimSolution solveInterceptFromTrajectory(double shooterX,
                                                            double shooterY,
                                                            double bulletSpeed,
                                                            PhysicsUtil.Trajectory opponentTrajectory) {
        if (!Double.isFinite(shooterX) || !Double.isFinite(shooterY)) {
            throw new IllegalArgumentException("Aim solve requires finite shooter coordinates");
        }
        if (!Double.isFinite(bulletSpeed) || bulletSpeed <= 0.0) {
            throw new IllegalArgumentException("Aim solve requires a positive finite bullet speed");
        }
        if (opponentTrajectory == null || opponentTrajectory.length() < 2) {
            throw new IllegalArgumentException("Aim solve requires an opponent trajectory with at least 2 states");
        }

        int maxTicks = opponentTrajectory.length() - 1;
        List<double[]> hitIntervals = new ArrayList<double[]>();
        double referenceAngle = Double.NaN;
        int firstIntersectTick = -1;
        for (int flightTicks = 1; flightTicks <= maxTicks; flightTicks++) {
            PhysicsUtil.PositionState current = opponentTrajectory.stateAt(flightTicks);
            double innerRadius = bulletSpeed * (flightTicks - 1);
            double outerRadius = innerRadius + bulletSpeed;
            double minDistance = RobotHitbox.minDistance(shooterX, shooterY, current.x, current.y);
            if (outerRadius < minDistance) {
                continue;
            }
            double maxDistance = RobotHitbox.maxDistance(shooterX, shooterY, current.x, current.y);
            // Robocode bullets always outrun a bot, so once the inner radius has cleared the far
            // edge of the hitbox, no later tick can intersect it again.
            if (bulletSpeed > Rules.MAX_VELOCITY && innerRadius > maxDistance) {
                break;
            }
            double[] angularInterval = RobotHitbox.annulusAngularInterval(
                    shooterX,
                    shooterY,
                    innerRadius,
                    outerRadius,
                    current.x,
                    current.y);
            if (angularInterval == null) {
                continue;
            }
            if (firstIntersectTick < 0) {
                firstIntersectTick = flightTicks;
                referenceAngle = intervalMidpoint(angularInterval[0], angularInterval[1]);
            }
            hitIntervals.add(unwrapIntervalAroundReference(angularInterval[0], angularInterval[1], referenceAngle));
        }
        if (firstIntersectTick >= 0) {
            List<double[]> mergedIntervals = mergeIntervals(hitIntervals);
            double firingAngle = MathUtils.normalizeAngle(
                    (mergedIntervals.get(0)[0] + mergedIntervals.get(mergedIntervals.size() - 1)[1]) * 0.5);
            return new AimSolution(
                    opponentTrajectory.stateAt(firstIntersectTick),
                    firingAngle,
                    firstIntersectTick);
        }
        PhysicsUtil.PositionState lastState = opponentTrajectory.stateAt(opponentTrajectory.length() - 1);
        return new AimSolution(lastState, absoluteBearing(shooterX, shooterY, lastState.x, lastState.y), maxTicks);
    }

    private static List<double[]> mergeIntervals(List<double[]> intervals) {
        List<double[]> merged = new ArrayList<double[]>();
        if (intervals.isEmpty()) {
            return merged;
        }
        intervals.sort(Comparator.comparingDouble(a -> a[0]));
        double currentStart = intervals.get(0)[0];
        double currentEnd = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            double start = intervals.get(i)[0];
            double end = intervals.get(i)[1];
            if (start <= currentEnd) {
                currentEnd = Math.max(currentEnd, end);
            } else {
                merged.add(new double[]{currentStart, currentEnd});
                currentStart = start;
                currentEnd = end;
            }
        }
        merged.add(new double[]{currentStart, currentEnd});
        return merged;
    }

    private static double[] unwrapIntervalAroundReference(double start, double end, double referenceAngle) {
        double width = end - start;
        double midpoint = intervalMidpoint(start, end);
        double unwrappedMidpoint = unwrapNear(midpoint, referenceAngle);
        double halfWidth = width * 0.5;
        return new double[]{unwrappedMidpoint - halfWidth, unwrappedMidpoint + halfWidth};
    }

    private static double intervalMidpoint(double start, double end) {
        return start + (end - start) * 0.5;
    }

    private static double unwrapNear(double angle, double referenceAngle) {
        double fullCircle = Math.PI * 2.0;
        return angle + Math.rint((referenceAngle - angle) / fullCircle) * fullCircle;
    }

    private static double absoluteBearing(double sourceX, double sourceY, double targetX, double targetY) {
        return Math.atan2(targetX - sourceX, targetY - sourceY);
    }
}

