package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;

final class RamSimulator {
    private static final double ROBOT_HITBOX_SIZE = 36.0;
    private final RobocodeScoreUtil.HitScoreScratch hitScoreScratch = new RobocodeScoreUtil.HitScoreScratch();
    
    static final class Result {
        final PhysicsUtil.Trajectory adjustedTrajectory;
        final double ramScoreDealt;
        final double ramScoreTaken;
        final double ramEnergyLoss;
        final double ramEnemyEnergyLoss;
        final List<RamEvent> ramEvents;

        Result(PhysicsUtil.Trajectory adjustedTrajectory,
               double ramScoreDealt,
               double ramScoreTaken,
               double ramEnergyLoss,
               double ramEnemyEnergyLoss,
               List<RamEvent> ramEvents) {
            this.adjustedTrajectory = adjustedTrajectory;
            this.ramScoreDealt = ramScoreDealt;
            this.ramScoreTaken = ramScoreTaken;
            this.ramEnergyLoss = ramEnergyLoss;
            this.ramEnemyEnergyLoss = ramEnemyEnergyLoss;
            this.ramEvents = ramEvents;
        }
    }

    private static final class SimulatedEnemyState {
        final double x;
        final double y;
        final double heading;
        final double velocity;

        SimulatedEnemyState(double x, double y, double heading, double velocity) {
            this.x = x;
            this.y = y;
            this.heading = heading;
            this.velocity = velocity;
        }
    }

    private final Info info;

    RamSimulator(Info info) {
        this.info = info;
    }

    Result simulate(PhysicsUtil.Trajectory rawTrajectory, long startTime) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound || rawTrajectory.length() == 0) {
            return new Result(rawTrajectory, 0.0, 0.0, 0.0, 0.0, Collections.<RamEvent>emptyList());
        }

        double bfWidth = info.getBattlefieldWidth();
        double bfHeight = info.getBattlefieldHeight();
        PhysicsUtil.PositionState[] rawStates = rawTrajectory.states;
        EnemyInfo.PredictedPosition enemyAtStart = enemy.predictPositionAtTime(startTime, bfWidth, bfHeight);
        // Most candidate paths never physically overlap the enemy hitbox. In that common
        // case we can skip the expensive collision-adjusted replay entirely.
        // We check at each tick whether the robots are still close enough for collision
        // to be possible within the remaining ticks, and bail out early if not.
        double maxSpeed = 8.0 + Math.abs(enemy.velocity);
        double probeX = enemyAtStart.x;
        double probeY = enemyAtStart.y;
        double probeHeading = enemy.heading;
        double probeVelocity = enemy.velocity;
        double[] overlapProbeInstruction = new double[2];
        boolean hasPotentialRamOverlap = false;
        for (int i = 1; i < rawStates.length; i++) {
            double remainingReach = maxSpeed * (rawStates.length - i) + ROBOT_HITBOX_SIZE;
            if (Math.abs(rawStates[i].x - probeX) > remainingReach
                    || Math.abs(rawStates[i].y - probeY) > remainingReach) {
                break;
            }
            if (robotsOverlap(rawStates[i].x, rawStates[i].y, probeX, probeY)) {
                hasPotentialRamOverlap = true;
                break;
            }
            PhysicsUtil.computeMovementInstructionInto(
                    probeX, probeY, probeHeading, probeVelocity,
                    rawStates[i - 1].x, rawStates[i - 1].y,
                    overlapProbeInstruction);
            PhysicsUtil.PositionState probeNext = PhysicsUtil.calculateNextTick(
                    probeX, probeY, probeHeading, probeVelocity,
                    overlapProbeInstruction[0], overlapProbeInstruction[1],
                    bfWidth, bfHeight);
            probeX = probeNext.x;
            probeY = probeNext.y;
            probeHeading = probeNext.heading;
            probeVelocity = probeNext.velocity;
            if (robotsOverlap(rawStates[i].x, rawStates[i].y, probeX, probeY)) {
                hasPotentialRamOverlap = true;
                break;
            }
        }
        if (!hasPotentialRamOverlap) {
            return new Result(rawTrajectory, 0.0, 0.0, 0.0, 0.0, Collections.<RamEvent>emptyList());
        }

        PhysicsUtil.PositionState[] adjustedStates = null;

        double ourEnergy = Math.max(0.0, info.getRobot().getEnergy());
        double enemyEnergy = Math.max(0.0, enemy.energy);
        boolean ourAlive = ourEnergy > 0.0;
        boolean enemyAlive = enemy.alive;
        double cumulativeOurRamDamage = Math.max(0.0, info.getOurRammingDamageOnEnemyThisRound());
        double cumulativeEnemyRamDamage = Math.max(0.0, info.getEnemyRammingDamageOnUsThisRound());
        double ramScoreDealt = 0.0;
        double ramScoreTaken = 0.0;
        double ramEnergyLoss = 0.0;
        double ramEnemyEnergyLoss = 0.0;
        List<RamEvent> ramEvents = new ArrayList<>();

        SimulatedEnemyState enemyState = new SimulatedEnemyState(
                enemyAtStart.x, enemyAtStart.y, enemy.heading, enemy.velocity);
        double[] instruction = new double[2];
        double[] enemyInstruction = new double[2];
        boolean ourTurnLocked = false;
        boolean enemyTurnLocked = false;
        boolean ourPathAdjusted = false;

        for (int i = 1; i < rawStates.length; i++) {
            PhysicsUtil.PositionState previousOurState = ourPathAdjusted
                    ? adjustedStates[i - 1]
                    : rawStates[i - 1];
            PhysicsUtil.PositionState targetState = rawStates[i];
            PhysicsUtil.PositionState nextOurState = ourPathAdjusted
                    ? advancePlannedState(
                    previousOurState,
                    targetState,
                    ourTurnLocked,
                    instruction,
                    bfWidth,
                    bfHeight)
                    : targetState;
            ourTurnLocked = false;

            if (!ourAlive || !enemyAlive) {
                if (ourPathAdjusted) {
                    adjustedStates[i] = nextOurState;
                }
                continue;
            }

            if (robotsOverlap(nextOurState.x, nextOurState.y, enemyState.x, enemyState.y)
                    && isCollisionFault(
                    nextOurState.velocity,
                    nextOurState.heading,
                    nextOurState.x,
                    nextOurState.y,
                    enemyState.x,
                    enemyState.y)) {
                double ourEnergyBeforeCollision = ourEnergy;
                double enemyEnergyBeforeCollision = enemyEnergy;
                RobocodeScoreUtil.scoreRamHit(
                        enemyEnergyBeforeCollision,
                        cumulativeOurRamDamage,
                        false,
                        hitScoreScratch);
                double ourEnergyLossThisCollision = Math.min(
                        RobocodeScoreUtil.RAM_DAMAGE,
                        Math.max(0.0, ourEnergyBeforeCollision));
                double enemyEnergyLossThisCollision = Math.min(
                        RobocodeScoreUtil.RAM_DAMAGE,
                        Math.max(0.0, enemyEnergyBeforeCollision));
                double creditedDamage = hitScoreScratch.creditedDamage;
                double scoreDelta = hitScoreScratch.scoreDelta;
                boolean ourKillBonusApplied = hitScoreScratch.killBonusApplied;
                cumulativeOurRamDamage = hitScoreScratch.cumulativeCreditedDamage;
                ourEnergy = Math.max(0.0, ourEnergyBeforeCollision - ourEnergyLossThisCollision);
                enemyEnergy = Math.max(0.0, enemyEnergyBeforeCollision - enemyEnergyLossThisCollision);
                ramScoreDealt += scoreDelta;
                ramEnergyLoss += ourEnergyLossThisCollision;
                ramEnemyEnergyLoss += enemyEnergyLossThisCollision;

                if (!ourPathAdjusted) {
                    adjustedStates = new PhysicsUtil.PositionState[rawStates.length];
                    System.arraycopy(rawStates, 0, adjustedStates, 0, i);
                    ourPathAdjusted = true;
                }
                nextOurState = rollbackOurCollision(previousOurState, nextOurState);
                ourTurnLocked = true;
                if (enemyAlive && ourKillBonusApplied) {
                    enemyAlive = false;
                }
                ramEvents.add(new RamEvent(
                        startTime + i,
                        scoreDelta,
                        0.0,
                        ourEnergyLossThisCollision,
                        enemyEnergyLossThisCollision,
                        creditedDamage,
                        0.0,
                        ourKillBonusApplied,
                        false));
            }
            if (ourEnergy <= 0.0) {
                ourAlive = false;
            }
            if (!ourAlive || !enemyAlive) {
                if (ourPathAdjusted) {
                    adjustedStates[i] = nextOurState;
                }
                continue;
            }

            SimulatedEnemyState previousEnemyState = enemyState;
            enemyState = advanceEnemyState(
                    enemyState,
                    previousOurState.x,
                    previousOurState.y,
                    enemyTurnLocked,
                    enemyInstruction,
                    bfWidth,
                    bfHeight);
            enemyTurnLocked = false;

            if (robotsOverlap(nextOurState.x, nextOurState.y, enemyState.x, enemyState.y)
                    && isCollisionFault(
                    enemyState.velocity,
                    enemyState.heading,
                    enemyState.x,
                    enemyState.y,
                    nextOurState.x,
                    nextOurState.y)) {
                double ourEnergyBeforeCollision = ourEnergy;
                double enemyEnergyBeforeCollision = enemyEnergy;
                RobocodeScoreUtil.scoreRamHit(
                        ourEnergyBeforeCollision,
                        cumulativeEnemyRamDamage,
                        false,
                        hitScoreScratch);
                double ourEnergyLossThisCollision = Math.min(
                        RobocodeScoreUtil.RAM_DAMAGE,
                        Math.max(0.0, ourEnergyBeforeCollision));
                double enemyEnergyLossThisCollision = Math.min(
                        RobocodeScoreUtil.RAM_DAMAGE,
                        Math.max(0.0, enemyEnergyBeforeCollision));
                double creditedDamage = hitScoreScratch.creditedDamage;
                double scoreDelta = hitScoreScratch.scoreDelta;
                boolean enemyKillBonusApplied = hitScoreScratch.killBonusApplied;
                cumulativeEnemyRamDamage = hitScoreScratch.cumulativeCreditedDamage;
                ourEnergy = Math.max(0.0, ourEnergyBeforeCollision - ourEnergyLossThisCollision);
                enemyEnergy = Math.max(0.0, enemyEnergyBeforeCollision - enemyEnergyLossThisCollision);
                ramScoreTaken += scoreDelta;
                ramEnergyLoss += ourEnergyLossThisCollision;
                ramEnemyEnergyLoss += enemyEnergyLossThisCollision;

                enemyState = rollbackEnemyCollision(previousEnemyState, enemyState);
                enemyTurnLocked = true;
                if (ourAlive && enemyKillBonusApplied) {
                    ourAlive = false;
                }
                ramEvents.add(new RamEvent(
                        startTime + i,
                        0.0,
                        scoreDelta,
                        ourEnergyLossThisCollision,
                        enemyEnergyLossThisCollision,
                        0.0,
                        creditedDamage,
                        false,
                        enemyKillBonusApplied));
            }

            if (ourEnergy <= 0.0) {
                ourAlive = false;
            }
            if (ourPathAdjusted) {
                adjustedStates[i] = nextOurState;
            }
        }

        return new Result(
                ourPathAdjusted ? new PhysicsUtil.Trajectory(adjustedStates) : rawTrajectory,
                ramScoreDealt,
                ramScoreTaken,
                ramEnergyLoss,
                ramEnemyEnergyLoss,
                ramEvents);
    }

    private static PhysicsUtil.PositionState advancePlannedState(PhysicsUtil.PositionState current,
                                                                 PhysicsUtil.PositionState targetState,
                                                                 boolean turnLocked,
                                                                 double[] instruction,
                                                                 double bfWidth,
                                                                 double bfHeight) {
        PhysicsUtil.computeMovementInstructionInto(
                current.x,
                current.y,
                current.heading,
                current.velocity,
                targetState.x,
                targetState.y,
                instruction);
        if (turnLocked) {
            instruction[1] = 0.0;
        }
        return PhysicsUtil.calculateNextTick(
                current.x,
                current.y,
                current.heading,
                current.velocity,
                instruction[0],
                instruction[1],
                bfWidth,
                bfHeight);
    }

    private static SimulatedEnemyState advanceEnemyState(SimulatedEnemyState state,
                                                         double targetX,
                                                         double targetY,
                                                         boolean turnLocked,
                                                         double[] instruction,
                                                         double bfWidth,
                                                         double bfHeight) {
        PhysicsUtil.computeMovementInstructionInto(
                state.x,
                state.y,
                state.heading,
                state.velocity,
                targetX,
                targetY,
                instruction);
        if (turnLocked) {
            instruction[1] = 0.0;
        }
        PhysicsUtil.PositionState nextState = PhysicsUtil.calculateNextTick(
                state.x,
                state.y,
                state.heading,
                state.velocity,
                instruction[0],
                instruction[1],
                bfWidth,
                bfHeight);
        return new SimulatedEnemyState(nextState.x, nextState.y, nextState.heading, nextState.velocity);
    }

    private static PhysicsUtil.PositionState rollbackOurCollision(PhysicsUtil.PositionState previousOurState,
                                                                  PhysicsUtil.PositionState collidedOurState) {
        return new PhysicsUtil.PositionState(
                previousOurState.x,
                previousOurState.y,
                collidedOurState.heading,
                0.0,
                collidedOurState.hitWall,
                collidedOurState.wallHitDamage);
    }

    private static SimulatedEnemyState rollbackEnemyCollision(SimulatedEnemyState previousEnemyState,
                                                              SimulatedEnemyState collidedEnemyState) {
        return new SimulatedEnemyState(
                previousEnemyState.x,
                previousEnemyState.y,
                collidedEnemyState.heading,
                0.0);
    }

    private static boolean robotsOverlap(double x1, double y1, double x2, double y2) {
        return Math.abs(x1 - x2) < ROBOT_HITBOX_SIZE && Math.abs(y1 - y2) < ROBOT_HITBOX_SIZE;
    }

    private static boolean isCollisionFault(double velocity,
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
}

