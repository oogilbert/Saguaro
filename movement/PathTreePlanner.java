package oog.mega.saguaro.movement;

import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;

final class PathTreePlanner {
    private final MovementEngine movement;
    private final PathSegmentPlanner pathSegmentPlanner;

    PathTreePlanner(MovementEngine movement) {
        if (movement == null) {
            throw new IllegalArgumentException("PathTreePlanner requires non-null movement");
        }
        this.movement = movement;
        this.pathSegmentPlanner = new PathSegmentPlanner(movement);
    }

    void buildPathTree(PhysicsUtil.PositionState state, long stateTime,
                       List<Wave> waves, int waveIndex,
                       List<PhysicsUtil.PositionState> pathStates,
                       double firstTargetX, double firstTargetY,
                       double firstWaveTargetAngle,
                       double firstWaveSafeSpotX,
                       double firstWaveSafeSpotY,
                       Wave firstWave,
                       WaveStrategy firstWaveStrategy,
                       int firstWaveSafeSpotTick,
                       int firstLegDurationTicks,
                       List<CandidatePath> results,
                       double bfWidth, double bfHeight,
                       boolean useApproachVariants,
                       boolean allowFullVirtualFirstWaveVariants,
                       List<Wave> scoringWaves,
                       Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
                       Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
                       int safePointCount) {
        if (waveIndex >= waves.size()) {
            PhysicsUtil.PositionState[] statesArray =
                    pathStates.toArray(new PhysicsUtil.PositionState[0]);
            PhysicsUtil.Trajectory trajectory = new PhysicsUtil.Trajectory(statesArray);
            long startTime = stateTime - (statesArray.length - 1);
            results.add(movement.buildCandidatePath(
                    trajectory, startTime, scoringWaves, shadowCache,
                    firstTargetX, firstTargetY, firstWaveTargetAngle, firstWaveSafeSpotX, firstWaveSafeSpotY, firstWave,
                    firstWaveStrategy, firstWaveSafeSpotTick, firstLegDurationTicks, precomputedWaveData));
            return;
        }

        Wave wave = waves.get(waveIndex);

        if (wave.isVirtual) {
            long startTime = stateTime - (pathStates.size() - 1L);
            long fireTickOffset = wave.fireTime - startTime;
            if (fireTickOffset >= pathStates.size()) {
                int ticksUntilFire = (int) (wave.fireTime - stateTime);
                if (ticksUntilFire <= 0) {
                    throw new IllegalStateException(
                            "Virtual wave requires pre-fire expansion with non-positive time gap: "
                                    + "ticksUntilFire=" + ticksUntilFire
                                    + ", stateTime=" + stateTime
                                    + ", waveFireTime=" + wave.fireTime);
                }

                int basePathSize = pathStates.size();
                PhysicsUtil.Trajectory preFireTraj =
                        PhysicsUtil.simulateStopTrajectory(state, ticksUntilFire, bfWidth, bfHeight);
                appendTrajectoryStates(pathStates, preFireTraj);

                int preFireTicks = preFireTraj.length() - 1;
                PhysicsUtil.PositionState fireState = preFireTraj.stateAt(preFireTicks);

                buildPathTree(fireState, wave.fireTime, waves, waveIndex, pathStates,
                        firstTargetX, firstTargetY,
                        firstWaveTargetAngle, firstWaveSafeSpotX, firstWaveSafeSpotY, firstWave,
                        firstWaveStrategy, firstWaveSafeSpotTick, firstLegDurationTicks,
                        results, bfWidth, bfHeight, useApproachVariants, allowFullVirtualFirstWaveVariants,
                        scoringWaves, shadowCache,
                        precomputedWaveData, MovementEngine.DEFERRED_VIRTUAL_WAVE_SAFE_POINTS);
                truncatePathStates(pathStates, basePathSize);
                return;
            }
        }

        int nominalFirstContactTicks = PhysicsUtil.waveArrivalTick(wave, state.x, state.y, stateTime);
        nominalFirstContactTicks = Math.max(nominalFirstContactTicks, 1);
        // This is only a planning anchor for the current wave's safespot geometry.
        // Segment simulation still runs until actual tracked-wave first contact,
        // and exact leaf evaluation later scores the full overlap from the
        // resulting trajectory.

        double waveRefX;
        double waveRefY;
        GuessFactorDistribution waveDistribution;
        if (!wave.isVirtual) {
            waveRefX = movement.waveReferenceX(wave);
            waveRefY = movement.waveReferenceY(wave);
            waveDistribution = movement.queryDistributionForEnemyWaveAtFireTime(wave);
        } else {
            long startTime = stateTime - (pathStates.size() - 1L);
            long fireTickOffset = wave.fireTime - startTime;
            PhysicsUtil.PositionState fireState;
            if (fireTickOffset < 0) {
                fireState = pathStates.get(0);
            } else if (fireTickOffset < pathStates.size()) {
                fireState = pathStates.get((int) fireTickOffset);
            } else {
                throw new IllegalStateException(
                        "Virtual wave fire-time state missing from planning history: "
                                + "fireTickOffset=" + fireTickOffset
                                + ", pathStateCount=" + pathStates.size()
                                + ", waveFireTime=" + wave.fireTime
                                + ", pathStartTime=" + startTime);
            }
            waveRefX = fireState.x;
            waveRefY = fireState.y;
            MovementEngine.MotionContext simulatedMotionContext = MovementEngine.deriveMotionContext(
                    pathStates,
                    Math.max(0, Math.min((int) fireTickOffset, pathStates.size() - 1)),
                    bfWidth,
                    bfHeight);
            int lastNonZeroLateralDirectionSign = MovementEngine.deriveLastNonZeroLateralDirectionSign(
                    pathStates,
                    Math.max(0, Math.min((int) fireTickOffset, pathStates.size() - 1)),
                    wave.originX,
                    wave.originY);
            WaveContextFeatures.WaveContext simulatedContext = WaveContextFeatures.createWaveContext(
                    wave.originX,
                    wave.originY,
                    wave.speed,
                    wave.fireTime,
                    fireState.x,
                    fireState.y,
                    fireState.heading,
                    fireState.velocity,
                    simulatedMotionContext.accelerationSign,
                    simulatedMotionContext.ticksSinceVelocityReversal,
                    simulatedMotionContext.ticksSinceDecel,
                    lastNonZeroLateralDirectionSign,
                    bfWidth,
                    bfHeight,
                    scoringWaves,
                    wave);
                waveDistribution = movement.queryDistributionForContextOrDefault(
                        simulatedContext,
                        wave.originX,
                        wave.originY,
                        fireState.x,
                        fireState.y,
                        fireState.heading,
                        fireState.velocity,
                        wave.speed);
            }
        List<BulletShadowUtil.ShadowInterval> waveShadows =
                MovementEngine.shadowCacheForWave(shadowCache, wave);

        boolean isFirstPlannedWave = waveIndex == 0;
        boolean isExpandedVirtualFirstWave = isFirstPlannedWave
                && wave.isVirtual
                && allowFullVirtualFirstWaveVariants;
        // Safe-spot selection should use the part of the wave that is reachable
        // from the current planning state. For deferred virtual waves, `state`
        // has already been advanced to the simulated fire-time state above.
        double[] gfRange = movement.computeApproximateGFRange(
                wave, state, nominalFirstContactTicks, bfWidth, bfHeight, waveRefX, waveRefY);
        boolean isRealFirstWave = isFirstPlannedWave && !wave.isVirtual;
        int effectiveSafePointCount = (isRealFirstWave || isExpandedVirtualFirstWave)
                ? safePointCount
                : MovementEngine.LATER_WAVE_SAFE_POINTS;
        double nominalContactRadius = wave.getRadius(stateTime + nominalFirstContactTicks);
        double[] safeGFs = movement.findKSafestGFs(
                wave, effectiveSafePointCount, gfRange[0], gfRange[1], nominalContactRadius, waveRefX, waveRefY,
                waveShadows, waveDistribution);

        double referenceBearing = Math.atan2(waveRefX - wave.originX, waveRefY - wave.originY);
        double mea = MathUtils.maxEscapeAngle(wave.speed);

        for (double gf : safeGFs) {
            double targetAngle = MathUtils.gfToAngle(referenceBearing, gf, mea);
            double waveRadius = wave.getRadius(stateTime + nominalFirstContactTicks);
            double gfX = wave.originX + FastTrig.sin(targetAngle) * waveRadius;
            double gfY = wave.originY + FastTrig.cos(targetAngle) * waveRadius;
            gfX = MovementEngine.clampToField(gfX, bfWidth, true);
            gfY = MovementEngine.clampToField(gfY, bfHeight, false);

            double tangentCwX = FastTrig.cos(targetAngle);
            double tangentCwY = -FastTrig.sin(targetAngle);

            List<WaveStrategy> strategies = WaveStrategy.primaryVariants(
                    useApproachVariants && (isRealFirstWave || isExpandedVirtualFirstWave));
            int basePathSize = pathStates.size();

            for (WaveStrategy strategy : strategies) {
                PathSegmentPlanner.SegmentPlan segmentPlan = pathSegmentPlanner.buildStrategySegmentPlan(
                        wave,
                        state,
                        stateTime,
                        nominalFirstContactTicks,
                        targetAngle,
                        gfX,
                        gfY,
                        tangentCwX,
                        tangentCwY,
                        strategy,
                        bfWidth,
                        bfHeight);
                PhysicsUtil.Trajectory segment = segmentPlan.segment;
                double segFirstTargetX = segmentPlan.firstTargetX;
                double segFirstTargetY = segmentPlan.firstTargetY;
                int segmentTicks = segment.length() - 1;

                double thisFirstTargetX = Double.isNaN(firstTargetX) ? segFirstTargetX : firstTargetX;
                double thisFirstTargetY = Double.isNaN(firstTargetY) ? segFirstTargetY : firstTargetY;
                double thisTargetAngle = Double.isNaN(firstWaveTargetAngle) ? targetAngle : firstWaveTargetAngle;
                double thisSafeSpotX = Double.isNaN(firstWaveSafeSpotX) ? gfX : firstWaveSafeSpotX;
                double thisSafeSpotY = Double.isNaN(firstWaveSafeSpotY) ? gfY : firstWaveSafeSpotY;
                Wave thisFirstWave = firstWave == null ? wave : firstWave;
                WaveStrategy thisFirstWaveStrategy =
                        firstWaveStrategy == WaveStrategy.NONE ? strategy : firstWaveStrategy;
                int thisFirstLegDurationTicks =
                        firstLegDurationTicks < 0 ? segmentPlan.firstLegDurationTicks : firstLegDurationTicks;

                appendTrajectoryStates(pathStates, segment);
                int thisFirstWaveSafeSpotTick =
                        firstWaveSafeSpotTick < 0 ? pathStates.size() - 1 : firstWaveSafeSpotTick;

                PhysicsUtil.PositionState endState = segment.stateAt(segmentTicks);
                long endTime = stateTime + segmentTicks;

                buildPathTree(endState, endTime, waves, waveIndex + 1, pathStates,
                        thisFirstTargetX, thisFirstTargetY,
                        thisTargetAngle, thisSafeSpotX, thisSafeSpotY, thisFirstWave, thisFirstWaveStrategy,
                        thisFirstWaveSafeSpotTick, thisFirstLegDurationTicks,
                        results, bfWidth, bfHeight, false, allowFullVirtualFirstWaveVariants,
                        scoringWaves, shadowCache, precomputedWaveData,
                        safePointCount);
                truncatePathStates(pathStates, basePathSize);
            }
        }
    }

    private static void appendTrajectoryStates(List<PhysicsUtil.PositionState> pathStates,
                                               PhysicsUtil.Trajectory trajectory) {
        for (int i = 1; i < trajectory.states.length; i++) {
            pathStates.add(trajectory.states[i]);
        }
    }

    private static void truncatePathStates(List<PhysicsUtil.PositionState> pathStates, int targetSize) {
        while (pathStates.size() > targetSize) {
            pathStates.remove(pathStates.size() - 1);
        }
    }
}


