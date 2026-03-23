package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import robocode.Rules;

final class WaveIntersectionAnalyzer {
    private final MovementEngine movement;

    WaveIntersectionAnalyzer(MovementEngine movement) {
        this.movement = movement;
    }

    // Sums expected bullet damage and enemy energy gain across all waves for a trajectory.
    MovementEngine.PathDangerMetrics evaluatePathDangerMetrics(
            PhysicsUtil.Trajectory trajectory,
            long startTime,
            List<Wave> wavesToScore,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData) {
        return evaluatePathDangerMetrics(
                trajectory,
                startTime,
                wavesToScore,
                shadowCache,
                precomputedWaveData,
                0,
                trajectory.length() - 1,
                true);
    }

    MovementEngine.PathDangerMetrics evaluatePathDangerMetrics(
            PhysicsUtil.Trajectory trajectory,
            long startTime,
            List<Wave> wavesToScore,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            int startStateIndex,
            int endStateIndex,
            boolean includeStationaryTail) {
        double totalDanger = 0.0;
        double totalEnemyEnergyGain = 0.0;
        List<PathWaveIntersection> intersections = new ArrayList<>();

        for (Wave wave : wavesToScore) {
            MovementEngine.PrecomputedWaveData wavePrecomputed = precomputedWaveData != null ? precomputedWaveData.get(wave) : null;
            MovementEngine.WaveAnalysis waveAnalysis = analyzeWaveIntersection(
                    wave,
                    trajectory,
                    startTime,
                    MovementEngine.shadowCacheForWave(shadowCache, wave),
                    wavePrecomputed,
                    wavesToScore,
                    startStateIndex,
                    endStateIndex,
                    includeStationaryTail);
            totalDanger += waveAnalysis.dangerMetrics.expectedBulletDamageTaken;
            totalEnemyEnergyGain += waveAnalysis.dangerMetrics.expectedEnemyEnergyGain;
            if (waveAnalysis.intersection != null) {
                intersections.add(waveAnalysis.intersection);
            }
        }

        intersections.sort(Comparator.comparingLong(i -> i.firstContactTime));
        return new MovementEngine.PathDangerMetrics(totalDanger, totalEnemyEnergyGain, intersections);
    }

    // Collects wave intersection geometry for a path without computing aggregate danger.
    List<PathWaveIntersection> collectPathWaveIntersections(
            CandidatePath path,
            PathIntersectionContext context) {
        List<PathWaveIntersection> intersections = new ArrayList<>();
        if (path.trajectory.length() == 0) {
            return intersections;
        }

        for (Wave wave : context.wavesToScore) {
            PathWaveIntersection intersection = analyzeWaveIntersection(
                    wave,
                    path.trajectory,
                    path.startTime,
                    MovementEngine.shadowCacheForWave(context.shadowCache, wave),
                    null,
                    context.wavesToScore,
                    0,
                    path.trajectory.length() - 1,
                    true).intersection;
            if (intersection != null) {
                intersections.add(intersection);
            }
        }

        intersections.sort(Comparator.comparingLong(i -> i.firstContactTime));
        return intersections;
    }

    // Analyzes a single wave against a trajectory to compute hit probability and expected damage.
    //
    // 1. Resolves the reference frame: reference bearing, MEA, shadow intervals, and the
    //    opponent's firing distribution. Uses precomputed data when available; for virtual waves,
    //    simulates our position at the wave's fire time from the trajectory.
    // 2. Walks each tick of the trajectory finding where the wave's annulus overlaps our hitbox,
    //    converting each overlap into a guess factor interval.
    // 3. Handles the tail: after the trajectory ends, the robot is stationary at its last
    //    position, so checks remaining ticks where the wave still passes through.
    // 4. Merges GF intervals, subtracts bullet shadows, and integrates the firing distribution
    //    over exposed intervals to get hit probability, then converts to expected damage.
    private MovementEngine.WaveAnalysis analyzeWaveIntersection(
            Wave wave,
            PhysicsUtil.Trajectory trajectory,
            long startTime,
            List<BulletShadowUtil.ShadowInterval> waveShadows,
            MovementEngine.PrecomputedWaveData precomputed,
            List<Wave> wavesToScore,
            int startStateIndex,
            int endStateIndex,
            boolean includeStationaryTail) {
        double referenceBearing;
        double mea;
        List<BulletShadowUtil.WeightedGfInterval> mergedShadowGfIntervals;
        GuessFactorDistribution waveDistribution;
        if (!wave.isVirtual && precomputed != null) {
            referenceBearing = precomputed.referenceBearing;
            mea = precomputed.mea;
            mergedShadowGfIntervals = precomputed.mergedShadowGfIntervals;
            waveDistribution = movement.queryDistributionForEnemyWaveAtFireTime(wave);
        } else {
            double referenceX;
            double referenceY;
            if (!wave.isVirtual) {
                referenceX = movement.waveReferenceX(wave);
                referenceY = movement.waveReferenceY(wave);
                waveDistribution = movement.queryDistributionForEnemyWaveAtFireTime(wave);
            } else {
                int tickOffset = (int) (wave.fireTime - startTime);
                PhysicsUtil.PositionState fireState = trajectory.stateAt(tickOffset);
                referenceX = fireState.x;
                referenceY = fireState.y;
                MovementEngine.MotionContext simulatedMotionContext = MovementEngine.deriveMotionContext(
                        trajectory.states,
                        tickOffset,
                        movement.getBattlefieldWidth(),
                        movement.getBattlefieldHeight());
                int lastNonZeroLateralDirectionSign = MovementEngine.deriveLastNonZeroLateralDirectionSign(
                        trajectory.states,
                        tickOffset,
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
                        movement.getBattlefieldWidth(),
                        movement.getBattlefieldHeight(),
                        wavesToScore,
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

            referenceBearing = Math.atan2(
                    referenceX - wave.originX,
                    referenceY - wave.originY);
            mea = MathUtils.maxEscapeAngle(wave.speed);
            if (waveShadows.isEmpty()) {
                mergedShadowGfIntervals = java.util.Collections.emptyList();
            } else {
                mergedShadowGfIntervals = BulletShadowUtil.mergeAndClipWeightedIntervals(
                        BulletShadowUtil.allGfIntervals(waveShadows, referenceBearing, mea), -1.0, 1.0);
            }
        }

        List<double[]> baseIntervals = new ArrayList<>();
        long firstContactTime = Long.MAX_VALUE;
        PhysicsUtil.PositionState[] states = trajectory.states;
        int stateCount = states.length;

        for (int i = startStateIndex; i <= endStateIndex; i++) {
            PhysicsUtil.PositionState state = states[i];
            long time = startTime + i;
            double startRadius = wave.getRadius(time);
            double endRadius = startRadius + wave.speed;
            if (endRadius <= 0.0) {
                continue;
            }
            double innerRadius = Math.max(0.0, startRadius);
            double minDistance = wave.minDistanceToBody(state.x, state.y);
            if (endRadius < minDistance) {
                continue;
            }
            if (time < firstContactTime) {
                firstContactTime = time;
            }
            double maxDistance = wave.maxDistanceToBody(state.x, state.y);
            if (innerRadius > maxDistance) {
                continue;
            }
            addWaveIntersectionInterval(
                    baseIntervals, wave, state, innerRadius, endRadius, referenceBearing, mea,
                    minDistance, maxDistance);
        }

        if (includeStationaryTail && endStateIndex == stateCount - 1) {
            PhysicsUtil.PositionState lastState = states[stateCount - 1];
            long extraStartTime = startTime + stateCount;
            double minOverlapRadius = wave.minDistanceToBody(lastState.x, lastState.y);
            double maxOverlapRadius = wave.maxDistanceToBody(lastState.x, lastState.y);
            long overlapStartTime = (long) Math.ceil(minOverlapRadius / wave.speed + wave.fireTime);
            long overlapEndTime = (long) Math.floor(maxOverlapRadius / wave.speed + wave.fireTime);
            long extraOverlapStartTime = Math.max(extraStartTime, overlapStartTime);
            if (extraOverlapStartTime <= overlapEndTime) {
                if (extraOverlapStartTime < firstContactTime) {
                    firstContactTime = extraOverlapStartTime;
                }
                double remainingStartRadius = Math.max(0.0, wave.getRadius(extraOverlapStartTime));
                addWaveIntersectionInterval(
                        baseIntervals, wave, lastState,
                        remainingStartRadius, maxOverlapRadius,
                        referenceBearing, mea,
                        minOverlapRadius, maxOverlapRadius);
            }
        }

        List<double[]> mergedBaseIntervals = BulletShadowUtil.mergeAndClipIntervals(baseIntervals, -1.0, 1.0);
        List<BulletShadowUtil.WeightedGfInterval> exposedIntervals = new ArrayList<>();
        for (double[] interval : mergedBaseIntervals) {
            BulletShadowUtil.appendWeightedComplementOfMergedIntervals(
                    interval[0], interval[1], mergedShadowGfIntervals, exposedIntervals);
        }
        double hitProb = 0.0;
        for (BulletShadowUtil.WeightedGfInterval interval : exposedIntervals) {
            hitProb += interval.weight * waveDistribution.integrate(interval.startGf, interval.endGf);
        }

        double firePower = wave.getFirepower();
        double damage = Rules.getBulletDamage(firePower);
        double expectedBulletDamageTaken = hitProb * damage;
        double expectedEnemyEnergyGain = hitProb * 3.0 * firePower;
        PathWaveIntersection intersection = null;
        if (firstContactTime != Long.MAX_VALUE && !exposedIntervals.isEmpty()) {
            intersection = new PathWaveIntersection(
                    wave,
                    firstContactTime,
                    referenceBearing,
                    mea,
                    exposedIntervals,
                    waveDistribution);
        }
        return new MovementEngine.WaveAnalysis(
                new MovementEngine.WaveDangerMetrics(expectedBulletDamageTaken, expectedEnemyEnergyGain),
                intersection);
    }

    // Converts a wave-annulus/hitbox overlap into a guess factor interval and appends it.
    private static void addWaveIntersectionInterval(List<double[]> intervals,
                                                    Wave wave,
                                                    PhysicsUtil.PositionState state,
                                                    double innerRadius,
                                                    double outerRadius,
                                                    double referenceBearing,
                                                    double mea,
                                                    double precomputedMinDist,
                                                    double precomputedMaxDist) {
        double[] angularInterval = RobotHitbox.annulusAngularInterval(
                wave.originX, wave.originY, innerRadius, outerRadius, state.x, state.y,
                precomputedMinDist, precomputedMaxDist);
        if (angularInterval == null) {
            return;
        }
        double[] gfInterval = RobotHitbox.toGuessFactorInterval(
                angularInterval[0], angularInterval[1], referenceBearing, mea);
        intervals.add(gfInterval);
    }
}

