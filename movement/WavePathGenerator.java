package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

final class WavePathGenerator {
    private static final double DEFAULT_VIRTUAL_FIRE_POWER = 2.0;

    private final MovementEngine movement;
    private final PathTreePlanner pathTreePlanner;

    WavePathGenerator(MovementEngine movement) {
        if (movement == null) {
            throw new IllegalArgumentException("WavePathGenerator requires non-null movement");
        }
        this.movement = movement;
        this.pathTreePlanner = new PathTreePlanner(movement);
    }

    List<CandidatePath> generateCandidatePaths(
            PathGenerationContext context,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData) {
        if (context == null) {
            throw new IllegalArgumentException("WavePathGenerator requires a non-null context");
        }
        if (context.planningWaves == null || context.planningWaves.isEmpty()) {
            throw new IllegalArgumentException("WavePathGenerator requires at least one planning wave");
        }

        List<CandidatePath> results = new ArrayList<>();
        List<PhysicsUtil.PositionState> pathStates = new ArrayList<>();
        pathStates.add(context.startState);
        pathTreePlanner.buildPathTree(
                context.startState,
                context.currentTime,
                context.planningWaves,
                0,
                pathStates,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                null,
                WaveStrategy.NONE,
                -1,
                -1,
                results,
                context.bfWidth,
                context.bfHeight,
                true,
                false,
                context.scoringWaves,
                shadowCache,
                precomputedWaveData,
                MovementEngine.K_SAFE_POINTS);
        return results;
    }

    SurfSegmentRecommendation recommendFutureSurfSegment(PhysicsUtil.PositionState startState,
                                                         long currentTime,
                                                         double bfWidth,
                                                         double bfHeight,
                                                         List<Wave> baseScoringWaves,
                                                         PredictedOpponentState opponentStart,
                                                         OpponentDriveSimulator.Instruction opponentInstruction) {
        if (opponentStart == null || opponentInstruction == null || !opponentStart.isAlive()) {
            return null;
        }
        return movement.recommendationFromCandidates(generateFuturePrefirePhasePaths(
                startState,
                currentTime,
                bfWidth,
                bfHeight,
                baseScoringWaves,
                opponentStart,
                opponentInstruction));
    }

    private List<CandidatePath> generateFuturePrefirePhasePaths(PhysicsUtil.PositionState startState,
                                                                long currentTime,
                                                                double bfWidth,
                                                                double bfHeight,
                                                                List<Wave> baseScoringWaves,
                                                                PredictedOpponentState opponentStart,
                                                                OpponentDriveSimulator.Instruction opponentInstruction) {
        List<CandidatePath> results = new ArrayList<>();
        if (opponentStart.energy <= 0.0) {
            return results;
        }

        long fireTime = MovementEngine.estimateVirtualFireTime(currentTime, opponentStart.gunHeat);
        int ticksUntilFire = (int) Math.max(0L, fireTime - currentTime);
        if (ticksUntilFire == 0) {
            List<PhysicsUtil.PositionState> preFireStates = new ArrayList<>();
            preFireStates.add(startState);
            addPrefireScenarioPaths(
                    results,
                    currentTime,
                    bfWidth,
                    bfHeight,
                    baseScoringWaves,
                    opponentStart.energy,
                    opponentStart.lastDetectedBulletPower,
                    fireTime,
                    opponentStart.x,
                    opponentStart.y,
                    startState,
                    preFireStates,
                    startState.x,
                    startState.y,
                    Double.NaN,
                    Double.NaN,
                    preFireStates.size() - 1,
                    true);
            return results;
        }

        double bearingFromOpponent = Math.atan2(
                startState.x - opponentStart.x,
                startState.y - opponentStart.y);
        double[][] preFireTargets = MovementEngine.buildPrefireTargets(startState.x, startState.y, bearingFromOpponent);

        for (double[] target : preFireTargets) {
            double angle = Math.atan2(target[0] - startState.x, target[1] - startState.y);
            double dist = Math.min(
                    MovementEngine.PRE_FIRE_TARGET_DISTANCE,
                    MovementEngine.maxDistanceInField(startState.x, startState.y, angle, bfWidth, bfHeight));
            double targetX = startState.x + FastTrig.sin(angle) * dist;
            double targetY = startState.y + FastTrig.cos(angle) * dist;

            PhysicsUtil.Trajectory preFireTraj = PhysicsUtil.simulateTrajectory(
                    startState,
                    targetX,
                    targetY,
                    currentTime,
                    null,
                    fireTime,
                    PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                    bfWidth,
                    bfHeight);
            int preFireTicks = preFireTraj.length() - 1;
            PhysicsUtil.PositionState fireState = preFireTraj.stateAt(preFireTicks);
            PhysicsUtil.Trajectory opponentTrajectory = OpponentDriveSimulator.simulateTrajectory(
                    opponentStart.toPositionState(),
                    preFireTraj,
                    opponentInstruction,
                    preFireTicks,
                    bfWidth,
                    bfHeight);
            PhysicsUtil.PositionState opponentAtFireTime = opponentTrajectory.stateAt(preFireTicks);
            List<PhysicsUtil.PositionState> preFireStates = new ArrayList<>(Arrays.asList(preFireTraj.states));

            addPrefireScenarioPaths(
                    results,
                    currentTime,
                    bfWidth,
                    bfHeight,
                    baseScoringWaves,
                    opponentStart.energy,
                    opponentStart.lastDetectedBulletPower,
                    fireTime,
                    opponentAtFireTime.x,
                    opponentAtFireTime.y,
                    fireState,
                    preFireStates,
                    targetX,
                    targetY,
                    targetX,
                    targetY,
                    preFireStates.size() - 1,
                    false);
        }

        return results;
    }

    private void addPrefireScenarioPaths(List<CandidatePath> results,
                                         long currentTime,
                                         double bfWidth,
                                         double bfHeight,
                                         List<Wave> activeEnemyWaves,
                                         double opponentEnergyAtFireTime,
                                         double lastDetectedBulletPower,
                                         long fireTime,
                                         double opponentXAtFireTime,
                                         double opponentYAtFireTime,
                                         PhysicsUtil.PositionState fireState,
                                         List<PhysicsUtil.PositionState> preFireStates,
                                         double noPlanningFirstTargetX,
                                         double noPlanningFirstTargetY,
                                         double treeFirstTargetX,
                                         double treeFirstTargetY,
                                         int firstSegmentEndTick,
                                         boolean allowFullVirtualFirstWaveVariants) {
        double firePower = conservativeVirtualFirePower(
                opponentEnergyAtFireTime,
                lastDetectedBulletPower);
        MovementEngine.MotionContext fireStateMotionContext = MovementEngine.deriveMotionContext(
                preFireStates,
                preFireStates.size() - 1,
                bfWidth,
                bfHeight);
        int lastNonZeroLateralDirectionSign = MovementEngine.deriveLastNonZeroLateralDirectionSign(
                preFireStates,
                preFireStates.size() - 1,
                opponentXAtFireTime,
                opponentYAtFireTime);
        Wave virtualWave = movement.createVirtualEnemyWave(
                opponentXAtFireTime,
                opponentYAtFireTime,
                firePower,
                fireTime,
                fireState.x,
                fireState.y,
                fireState.heading,
                fireState.velocity,
                fireStateMotionContext.accelerationSign,
                fireStateMotionContext.ticksSinceVelocityReversal,
                fireStateMotionContext.ticksSinceDecel,
                lastNonZeroLateralDirectionSign,
                bfWidth,
                bfHeight,
                activeEnemyWaves);

        List<Wave> scoringWaves = new ArrayList<>(activeEnemyWaves);
        scoringWaves.add(virtualWave);
        List<Wave> planningWaves = MovementEngine.buildPlanningWavesForState(
                scoringWaves,
                fireState.x,
                fireState.y,
                fireTime);

        if (planningWaves.isEmpty()) {
            movement.addCandidatePathFromStates(
                    results,
                    preFireStates,
                    currentTime,
                    scoringWaves,
                    noPlanningFirstTargetX,
                    noPlanningFirstTargetY,
                    firstSegmentEndTick);
            return;
        }

        Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache =
                movement.buildBaseShadowCacheForPlanning(scoringWaves);
        Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData =
                movement.buildPrecomputedWaveDataForPlanning(scoringWaves, shadowCache);
        pathTreePlanner.buildPathTree(
                fireState,
                fireTime,
                planningWaves,
                0,
                preFireStates,
                treeFirstTargetX,
                treeFirstTargetY,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                null,
                WaveStrategy.NONE,
                firstSegmentEndTick,
                firstSegmentEndTick,
                results,
                bfWidth,
                bfHeight,
                true,
                allowFullVirtualFirstWaveVariants,
                scoringWaves,
                shadowCache,
                precomputedWaveData,
                MovementEngine.K_SAFE_POINTS);
    }

    private static double conservativeVirtualFirePower(double opponentEnergyAtFireTime,
                                                       double lastDetectedBulletPower) {
        double historicalPower = Double.isFinite(lastDetectedBulletPower)
                ? clamp(lastDetectedBulletPower, 0.1, 3.0)
                : DEFAULT_VIRTUAL_FIRE_POWER;
        double availablePower = opponentEnergyAtFireTime >= 0.1
                ? Math.min(3.0, opponentEnergyAtFireTime)
                : 0.1;
        return Math.min(availablePower, historicalPower);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}


