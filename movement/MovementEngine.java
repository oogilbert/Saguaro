package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import robocode.Rules;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.DefaultDistribution;
import oog.mega.saguaro.math.GuessFactorDistributionHandle;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.perfectprediction.ReactiveOpponentPredictor;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public class MovementEngine implements MovementController {
    static final int K_SAFE_POINTS = 2;
    static final int DEFERRED_VIRTUAL_WAVE_SAFE_POINTS = 1;
    static final int LATER_WAVE_SAFE_POINTS = 1;
    static final int MAX_WAVE_DEPTH = 2;
    static final double PRE_FIRE_TARGET_DISTANCE = 1000.0;
    static final double CIRCULAR_WAVE_APPROX_RADIUS = RobotHitbox.HALF_WIDTH;
    private static final double GF_RANGE_OVERLAP_EPSILON = 1e-5;
    private static final int OPTIMISTIC_RAY_FIXED_POINT_ITERS = 4;
    private static final double OPTIMISTIC_RAY_INTERSECTION_EPS = 1e-9;
    static final double MAX_EFFECTIVE_VIRTUAL_GUN_HEAT = 1.6;
    // Debug toggle to isolate virtual-wave effects during tuning.
    static final boolean ENABLE_VIRTUAL_WAVES = true;
    private static final int DISTRIBUTION_CACHE_CAPACITY = 24;
    private static final Random random = new Random();

    private final Info info;
    private final RamSimulator ramSimulator;
    private final WaveIntersectionAnalyzer waveIntersectionAnalyzer;
    private final WaveShadowCacheBuilder waveShadowCacheBuilder;
    private final PathGenerator pathGenerator;
    private final WavePathGenerator wavePathGenerator;
    private final MovementWavePlanner movementWavePlanner;
    private final GuessFactorDangerAnalyzer guessFactorDangerAnalyzer;
    private final DefaultDistribution defaultDistribution = new DefaultDistribution();
    private PathPlanningDiagnostics latestPathPlanningDiagnostics;
    private long distributionCacheTime = Long.MIN_VALUE;
    private int distributionCacheSize;
    private final WaveContextFeatures.WaveContext[] distributionCacheContexts =
            new WaveContextFeatures.WaveContext[DISTRIBUTION_CACHE_CAPACITY];
    private final GuessFactorDistributionHandle[] distributionCacheHandles =
            new GuessFactorDistributionHandle[DISTRIBUTION_CACHE_CAPACITY];

    public MovementEngine(Info info) {
        this.info = info;
        this.ramSimulator = new RamSimulator(info);
        this.waveIntersectionAnalyzer = new WaveIntersectionAnalyzer(this);
        this.waveShadowCacheBuilder = new WaveShadowCacheBuilder(this);
        this.pathGenerator = new PathGenerator(this);
        this.wavePathGenerator = new WavePathGenerator(this);
        this.movementWavePlanner = new MovementWavePlanner(this, info);
        this.guessFactorDangerAnalyzer = new GuessFactorDangerAnalyzer();
        this.latestPathPlanningDiagnostics = null;
    }

    static final class PathPlanningDiagnostics {
        final long planningTime;
        final int planningWaveCount;
        final int planningHorizonTicks;
        final int candidatePathCount;
        final int anchoredFamilyCount;
        final int randomFamilyCount;
        final int reusedFamilyCount;
        final int primaryFamilyRebuildCount;
        final int primaryFamilyMutationCount;
        final int secondaryFamilyRebuildCount;
        final int secondaryFamilyMutationCount;

        PathPlanningDiagnostics(long planningTime,
                                int planningWaveCount,
                                int planningHorizonTicks,
                                int candidatePathCount,
                                int anchoredFamilyCount,
                                int randomFamilyCount,
                                int reusedFamilyCount,
                                int primaryFamilyRebuildCount,
                                int primaryFamilyMutationCount,
                                int secondaryFamilyRebuildCount,
                                int secondaryFamilyMutationCount) {
            this.planningTime = planningTime;
            this.planningWaveCount = planningWaveCount;
            this.planningHorizonTicks = planningHorizonTicks;
            this.candidatePathCount = candidatePathCount;
            this.anchoredFamilyCount = anchoredFamilyCount;
            this.randomFamilyCount = randomFamilyCount;
            this.reusedFamilyCount = reusedFamilyCount;
            this.primaryFamilyRebuildCount = primaryFamilyRebuildCount;
            this.primaryFamilyMutationCount = primaryFamilyMutationCount;
            this.secondaryFamilyRebuildCount = secondaryFamilyRebuildCount;
            this.secondaryFamilyMutationCount = secondaryFamilyMutationCount;
        }

        String describe() {
            return String.format(
                    java.util.Locale.US,
                    "planningTime=%d waves=%d horizon=%d candidates=%d anchors=%d random=%d reused=%d primary=%d/%d secondary=%d/%d",
                    planningTime,
                    planningWaveCount,
                    planningHorizonTicks,
                    candidatePathCount,
                    anchoredFamilyCount,
                    randomFamilyCount,
                    reusedFamilyCount,
                    primaryFamilyRebuildCount,
                    primaryFamilyMutationCount,
                    secondaryFamilyRebuildCount,
                    secondaryFamilyMutationCount);
        }
    }

    public GuessFactorDistribution getDistributionForContext(WaveContextFeatures.WaveContext context) {
        GuessFactorDistributionHandle handle = distributionHandleForContext(context);
        return handle != null ? handle.exact() : defaultDistribution;
    }

    GuessFactorDistribution queryDistributionForContext(WaveContextFeatures.WaveContext context) {
        GuessFactorDistributionHandle handle = distributionHandleForContext(context);
        return handle != null ? handle.query() : defaultDistribution;
    }

    GuessFactorDistribution queryDistributionForContextOrDefault(WaveContextFeatures.WaveContext context,
                                                                 double sourceX,
                                                                 double sourceY,
                                                                 double targetX,
                                                                 double targetY,
                                                                 double targetHeading,
                                                                 double targetVelocity,
                                                                 double bulletSpeed) {
        GuessFactorDistributionHandle handle = distributionHandleForContext(context);
        if (handle != null) {
            return handle.query();
        }
        return DefaultDistribution.forTargetingState(
                sourceX,
                sourceY,
                targetX,
                targetY,
                targetHeading,
                targetVelocity,
                bulletSpeed);
    }

    GuessFactorDistributionHandle distributionHandleForContextOrDefault(WaveContextFeatures.WaveContext context,
                                                                        double sourceX,
                                                                        double sourceY,
                                                                        double targetX,
                                                                        double targetY,
                                                                        double targetHeading,
                                                                        double targetVelocity,
                                                                        double bulletSpeed) {
        GuessFactorDistributionHandle handle = distributionHandleForContext(context);
        if (handle != null) {
            return handle;
        }
        return new GuessFactorDistributionHandle(DefaultDistribution.forTargetingState(
                sourceX,
                sourceY,
                targetX,
                targetY,
                targetHeading,
                targetVelocity,
                bulletSpeed));
    }

    GuessFactorDistribution distributionForEnemyWaveAtFireTime(Wave wave) {
        return distributionHandleForEnemyWaveAtFireTime(wave).exact();
    }

    public GuessFactorDistribution queryDistributionForEnemyWaveAtFireTime(Wave wave) {
        return distributionHandleForEnemyWaveAtFireTime(wave).query();
    }

    GuessFactorDistributionHandle distributionHandleForContext(WaveContextFeatures.WaveContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Movement distribution context must be non-null");
        }
        ensureDistributionCacheTime(info.getRobot().getTime());
        int cacheIndex = findDistributionCacheIndex(context);
        if (cacheIndex >= 0) {
            return distributionCacheHandles[cacheIndex];
        }

        GuessFactorDistribution exactDistribution = info.getObservationProfile().createMovementDistribution(context);
        if (exactDistribution == null) {
            return null;
        }
        GuessFactorDistributionHandle handle = new GuessFactorDistributionHandle(exactDistribution);
        if (distributionCacheSize < DISTRIBUTION_CACHE_CAPACITY) {
            distributionCacheContexts[distributionCacheSize] = context;
            distributionCacheHandles[distributionCacheSize] = handle;
            distributionCacheSize++;
        }
        return handle;
    }

    private GuessFactorDistributionHandle distributionHandleForEnemyWaveAtFireTime(Wave wave) {
        if (wave.fireTimeContext == null) {
            throw new IllegalStateException("Enemy wave missing fire-time context");
        }
        if (wave.fireTimeDistributionHandle == null) {
            GuessFactorDistributionHandle learnedHandle = distributionHandleForContext(wave.fireTimeContext);
            wave.fireTimeDistributionHandle = learnedHandle != null
                    ? learnedHandle
                    : new GuessFactorDistributionHandle(DefaultDistribution.forObservedEnemyWave(wave));
        }
        return wave.fireTimeDistributionHandle;
    }

    private void ensureDistributionCacheTime(long currentTime) {
        if (distributionCacheTime == currentTime) {
            return;
        }
        distributionCacheTime = currentTime;
        distributionCacheSize = 0;
    }

    private int findDistributionCacheIndex(WaveContextFeatures.WaveContext context) {
        for (int i = 0; i < distributionCacheSize; i++) {
            if (context.equals(distributionCacheContexts[i])) {
                return i;
            }
        }
        return -1;
    }

    public static class PlannedShot {
        public final double originX;
        public final double originY;
        public final long fireTime;
        public final double power;
        public final double firingAngle;

        public PlannedShot(double originX, double originY, long fireTime, double power, double firingAngle) {
            if (power < 0.1 || power > 3.0) {
                throw new IllegalStateException("PlannedShot power must be in [0.1, 3.0], got " + power);
            }
            if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(firingAngle)) {
                throw new IllegalStateException("PlannedShot requires finite geometry");
            }
            this.originX = originX;
            this.originY = originY;
            this.fireTime = fireTime;
            this.power = power;
            this.firingAngle = firingAngle;
        }

        public Wave toWave() {
            return new Wave(originX, originY, Wave.bulletSpeed(power), fireTime, false, firingAngle);
        }
    }

    public PathGenerationContext createPathGenerationContext() {
        RobotSnapshot robot = info.captureRobotSnapshot();
        double x = robot.x;
        double y = robot.y;
        double heading = robot.heading;
        double velocity = robot.velocity;
        double bfWidth = info.getBattlefieldWidth();
        double bfHeight = info.getBattlefieldHeight();
        long currentTime = robot.time;
        PhysicsUtil.PositionState startState = new PhysicsUtil.PositionState(x, y, heading, velocity);

        MovementWavePlanner.WaveSelection waveSelection = movementWavePlanner.selectWavesForState(
                currentTime, x, y, startState.heading, startState.velocity);
        return assemblePathGenerationContext(
                startState,
                currentTime,
                bfWidth,
                bfHeight,
                waveSelection);
    }

    private PathGenerationContext assemblePathGenerationContext(PhysicsUtil.PositionState startState,
                                                                long currentTime,
                                                                double bfWidth,
                                                                double bfHeight,
                                                                MovementWavePlanner.WaveSelection waveSelection) {
        double opponentReferenceX = Double.NaN;
        double opponentReferenceY = Double.NaN;
        if (waveSelection.opponent != null
                && waveSelection.opponent.alive
                && waveSelection.opponent.seenThisRound
                && Double.isFinite(waveSelection.opponent.x)
                && Double.isFinite(waveSelection.opponent.y)) {
            opponentReferenceX = waveSelection.opponent.x;
            opponentReferenceY = waveSelection.opponent.y;
        }
        Map<Wave, List<BulletShadowUtil.ShadowInterval>> baseShadowCache =
                waveShadowCacheBuilder.buildBaseShadowCache(waveSelection.scoringWaves);
        return new PathGenerationContext(
                startState,
                currentTime,
                bfWidth,
                bfHeight,
                waveSelection.activeEnemyWaves,
                waveSelection.opponent,
                opponentReferenceX,
                opponentReferenceY,
                waveSelection.scoringWaves,
                waveSelection.planningWaves,
                baseShadowCache);
    }

    public List<CandidatePath> generateCandidatePaths(PathGenerationContext context) {
        return generateCandidatePaths(context, Collections.<CandidatePath>emptyList());
    }

    @Override
    public List<CandidatePath> generateCandidatePaths(PathGenerationContext context,
                                                      List<CandidatePath> carriedForwardFamilies) {
        PhysicsUtil.PositionState startState = context.startState;
        long currentTime = context.currentTime;
        double bfWidth = context.bfWidth;
        double bfHeight = context.bfHeight;
        double x = startState.x;
        double y = startState.y;

        List<Wave> scoringWaves = context.scoringWaves;
        List<Wave> planningWaves = context.planningWaves;
        Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache = context.baseShadowCache;
        Map<Wave, PrecomputedWaveData> precomputedWaveData =
                waveShadowCacheBuilder.buildPrecomputedWaveData(scoringWaves, shadowCache);

        List<CandidatePath> results = new ArrayList<>();

        if (planningWaves.isEmpty()) {
            generateNoWaveFallbackPaths(
                    results,
                    startState,
                    currentTime,
                    bfWidth,
                    bfHeight,
                    x,
                    y,
                    scoringWaves,
                    shadowCache,
                    precomputedWaveData);
            latestPathPlanningDiagnostics = new PathPlanningDiagnostics(
                    currentTime,
                    0,
                    0,
                    results.size(),
                    0,
                    results.size(),
                    0,
                    0,
                    0,
                    0,
                    0);
            return results;
        }

        return pathGenerator.generateCandidatePaths(
                context,
                shadowCache,
                precomputedWaveData,
                carriedForwardFamilies);
    }

    void recordPathPlanningDiagnostics(PathPlanningDiagnostics diagnostics) {
        latestPathPlanningDiagnostics = diagnostics;
    }

    @Override
    public String describeLatestPathPlanningDiagnostics() {
        return latestPathPlanningDiagnostics != null
                ? latestPathPlanningDiagnostics.describe()
                : "planning=n/a";
    }

    private void generateNoWaveFallbackPaths(List<CandidatePath> results,
                                             PhysicsUtil.PositionState startState,
                                             long currentTime,
                                             double bfWidth,
                                             double bfHeight,
                                             double x,
                                             double y,
                                             List<Wave> scoringWaves,
                                             Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
                                             Map<Wave, PrecomputedWaveData> precomputedWaveData) {
        // No planned waves: generate a few random 1-tick movement options.
        for (int i = 0; i < 5; i++) {
            double targetX, targetY;
            do {
                targetX = x + (random.nextDouble() * 100 - 50);
                targetY = y + (random.nextDouble() * 100 - 50);
            } while (!PhysicsUtil.isWithinBattlefield(targetX, targetY, bfWidth, bfHeight));

            PhysicsUtil.Trajectory traj = PhysicsUtil.simulateTrajectory(
                    startState, targetX, targetY, 1, bfWidth, bfHeight);
            results.add(buildCandidatePath(
                    traj, currentTime, scoringWaves, shadowCache,
                    targetX, targetY,
                    Double.NaN, Double.NaN, Double.NaN,
                    null, WaveStrategy.NONE, -1, 1, precomputedWaveData,
                    Collections.<PathLeg>emptyList(),
                    fallbackFamilyId(currentTime, i, targetX, targetY)));
        }
    }

    static final class MotionContext {
        public final int accelerationSign;
        public final int ticksSinceVelocityReversal;
        public final int ticksSinceDecel;
        public final int lastNonZeroVelocitySign;

        MotionContext(int accelerationSign,
                      int ticksSinceVelocityReversal,
                      int ticksSinceDecel,
                      int lastNonZeroVelocitySign) {
            this.accelerationSign = accelerationSign;
            this.ticksSinceVelocityReversal = ticksSinceVelocityReversal;
            this.ticksSinceDecel = ticksSinceDecel;
            this.lastNonZeroVelocitySign = lastNonZeroVelocitySign;
        }
    }

    private static int signWithEpsilon(double value) {
        if (value > 1e-9) {
            return 1;
        }
        if (value < -1e-9) {
            return -1;
        }
        return 0;
    }

    static MotionContext deriveMotionContext(PhysicsUtil.PositionState[] states,
                                             int tickOffset,
                                             double bfWidth,
                                             double bfHeight) {
        return deriveMotionContext(states.length, index -> states[index], tickOffset, bfWidth, bfHeight);
    }

    static MotionContext deriveMotionContext(List<PhysicsUtil.PositionState> states,
                                             int tickOffset,
                                             double bfWidth,
                                             double bfHeight) {
        return deriveMotionContext(states.size(), states::get, tickOffset, bfWidth, bfHeight);
    }

    private static MotionContext deriveMotionContext(int stateCount,
                                                     java.util.function.IntFunction<PhysicsUtil.PositionState> stateAt,
                                                     int tickOffset,
                                                     double bfWidth,
                                                     double bfHeight) {
        int boundedTickOffset = Math.max(0, Math.min(tickOffset, stateCount - 1));
        PhysicsUtil.PositionState state = stateAt.apply(boundedTickOffset);

        int accelerationSign = 0;
        if (boundedTickOffset > 0) {
            accelerationSign = signWithEpsilon(state.velocity - stateAt.apply(boundedTickOffset - 1).velocity);
        }

        int sourceIndex = boundedTickOffset;
        int currentVelocitySign = signWithEpsilon(stateAt.apply(sourceIndex).velocity);
        while (sourceIndex > 0 && currentVelocitySign == 0) {
            sourceIndex--;
            currentVelocitySign = signWithEpsilon(stateAt.apply(sourceIndex).velocity);
        }
        int lastNonZeroVelocitySign = currentVelocitySign;

        int ticksSinceVelocityReversal = boundedTickOffset - sourceIndex;
        if (currentVelocitySign == 0) {
            ticksSinceVelocityReversal = 0;
        } else {
            for (int i = sourceIndex - 1; i >= 0; i--) {
                int previousVelocitySign = signWithEpsilon(stateAt.apply(i).velocity);
                if (previousVelocitySign == 0) {
                    ticksSinceVelocityReversal++;
                    continue;
                }
                if (previousVelocitySign != currentVelocitySign) {
                    break;
                }
                ticksSinceVelocityReversal++;
            }
        }

        int ticksSinceDecel = 0;
        for (int i = boundedTickOffset; i > 0; i--) {
            double currentAbsVelocity = Math.abs(stateAt.apply(i).velocity);
            double previousAbsVelocity = Math.abs(stateAt.apply(i - 1).velocity);
            if (currentAbsVelocity < previousAbsVelocity - 1e-9) {
                break;
            }
            ticksSinceDecel++;
        }

        return new MotionContext(
                accelerationSign,
                ticksSinceVelocityReversal,
                ticksSinceDecel,
                lastNonZeroVelocitySign);
    }

    static int deriveLastNonZeroLateralDirectionSign(PhysicsUtil.PositionState[] states,
                                                     int tickOffset,
                                                     double sourceX,
                                                     double sourceY) {
        return deriveLastNonZeroLateralDirectionSign(states.length, index -> states[index], tickOffset, sourceX, sourceY);
    }

    static int deriveLastNonZeroLateralDirectionSign(List<PhysicsUtil.PositionState> states,
                                                     int tickOffset,
                                                     double sourceX,
                                                     double sourceY) {
        return deriveLastNonZeroLateralDirectionSign(states.size(), states::get, tickOffset, sourceX, sourceY);
    }

    private static int deriveLastNonZeroLateralDirectionSign(int stateCount,
                                                             java.util.function.IntFunction<PhysicsUtil.PositionState> stateAt,
                                                             int tickOffset,
                                                             double sourceX,
                                                             double sourceY) {
        int boundedTickOffset = Math.max(0, Math.min(tickOffset, stateCount - 1));
        for (int i = boundedTickOffset; i >= 0; i--) {
            PhysicsUtil.PositionState state = stateAt.apply(i);
            int lateralDirectionSign = WaveContextFeatures.computeLateralDirectionSign(
                    sourceX,
                    sourceY,
                    state.x,
                    state.y,
                    state.heading,
                    state.velocity);
            if (lateralDirectionSign != 0) {
                return lateralDirectionSign;
            }
        }
        return 0;
    }

    private int solveOptimisticSafeRayTicks(Wave wave,
                                            PhysicsUtil.PositionState state,
                                            long stateTime,
                                            int initialTicksUntilArrival,
                                            double targetAngle,
                                            boolean moveOutward,
                                            double bfWidth,
                                            double bfHeight) {
        if (wave.speed <= 0.0) {
            throw new IllegalStateException("Wave speed must be positive for optimistic direct planning");
        }
        int ticks = Math.max(1, initialTicksUntilArrival);
        int previousTicks = -1;
        int previousPreviousTicks = -1;

        for (int i = 0; i < OPTIMISTIC_RAY_FIXED_POINT_ITERS; i++) {
            double desiredRadius = optimisticSafeRayIntersectionRadius(
                    wave, state, targetAngle, ticks, moveOutward, bfWidth, bfHeight);
            int nextTicks = estimateWaveContactTicksAtRadius(wave, stateTime, desiredRadius);
            if (nextTicks == ticks) {
                return ticks;
            }
            if (nextTicks == previousPreviousTicks && ticks == previousTicks) {
                return moveOutward ? Math.max(ticks, nextTicks) : Math.min(ticks, nextTicks);
            }
            previousPreviousTicks = previousTicks;
            previousTicks = ticks;
            ticks = nextTicks;
        }
        return ticks;
    }

    private double optimisticSafeRayIntersectionRadius(Wave wave,
                                                       PhysicsUtil.PositionState state,
                                                       double targetAngle,
                                                       int ticks,
                                                       boolean moveOutward,
                                                       double bfWidth,
                                                       double bfHeight) {
        double maxRadiusOnRay = Math.max(0.0, maxRadiusOnAngleWithinField(wave, targetAngle, bfWidth, bfHeight));
        if (maxRadiusOnRay <= 0.0) {
            return 0.0;
        }

        double travelDistance = optimisticTravelDistance(Math.abs(state.velocity), Math.max(1, ticks));
        double dirX = FastTrig.sin(targetAngle);
        double dirY = FastTrig.cos(targetAngle);
        double offsetX = wave.originX - state.x;
        double offsetY = wave.originY - state.y;

        // Solve |(waveOrigin + dir * r) - state|^2 = travelDistance^2 for ray parameter r.
        double dot = dirX * offsetX + dirY * offsetY;
        double projectionRadius = -dot;
        double c = offsetX * offsetX + offsetY * offsetY - travelDistance * travelDistance;
        double discriminant = dot * dot - c;

        double chosenRadius;
        if (discriminant >= -OPTIMISTIC_RAY_INTERSECTION_EPS) {
            double sqrtDisc = Math.sqrt(Math.max(0.0, discriminant));
            double nearRadius = projectionRadius - sqrtDisc;
            double farRadius = projectionRadius + sqrtDisc;
            chosenRadius = moveOutward ? farRadius : nearRadius;
        } else {
            // No intersection for this tick estimate; use nearest point on the ray.
            chosenRadius = projectionRadius;
        }

        if (!Double.isFinite(chosenRadius)) {
            throw new IllegalStateException("Non-finite optimistic safespot-ray radius");
        }
        return Math.max(0.0, Math.min(maxRadiusOnRay, chosenRadius));
    }

    private static int estimateWaveContactTicksAtRadius(Wave wave, long stateTime, double targetRadius) {
        double currentWaveReach = wave.getRadius(stateTime) + CIRCULAR_WAVE_APPROX_RADIUS;
        double remaining = targetRadius - currentWaveReach;
        if (remaining <= 0.0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(remaining / wave.speed));
    }

    private static double optimisticTravelDistance(double initialAbsVelocity, int ticks) {
        if (ticks <= 0) {
            return 0.0;
        }
        double clampedInitial = Math.max(0.0, Math.min(Rules.MAX_VELOCITY, initialAbsVelocity));
        int rampTicks = (int) Math.ceil(Math.max(0.0, Rules.MAX_VELOCITY - clampedInitial));
        int acceleratedTicks = Math.min(ticks, rampTicks);
        double acceleratedTicksDouble = acceleratedTicks;
        double acceleratedDistance =
                acceleratedTicksDouble * clampedInitial
                        + acceleratedTicksDouble * (acceleratedTicksDouble - 1.0) * 0.5;
        int cruiseTicks = ticks - acceleratedTicks;
        return acceleratedDistance + cruiseTicks * Rules.MAX_VELOCITY;
    }

    /**
     * Finds the K safest guess factors on a wave by locating local minima of the
     * hit-probability function inside the reachable GF interval.
     */
    double[] findKSafestGFs(Wave wave, int k, double minGF, double maxGF,
                            double hitboxDistance,
                            double refX, double refY,
                            List<BulletShadowUtil.ShadowInterval> waveShadows,
                            GuessFactorDistribution distribution) {
        return guessFactorDangerAnalyzer.findKSafestGFs(
                wave, k, minGF, maxGF, hitboxDistance, refX, refY, waveShadows, distribution);
    }

    /**
     * Converts a GF on a wave to a target position.
     */
    double[] gfToTargetPosition(Wave wave, double gf, double refX, double refY,
                                        long arrivalTime, double bfWidth, double bfHeight) {
        double referenceBearing = Math.atan2(refX - wave.originX, refY - wave.originY);
        double mea = MathUtils.maxEscapeAngle(wave.speed);
        double targetAngle = MathUtils.gfToAngle(referenceBearing, gf, mea);
        double waveRadius = wave.getRadius(arrivalTime);

        double targetX = wave.originX + FastTrig.sin(targetAngle) * waveRadius;
        double targetY = wave.originY + FastTrig.cos(targetAngle) * waveRadius;

        // Clamp to battlefield bounds minus wall margin
        targetX = clampToField(targetX, bfWidth, true);
        targetY = clampToField(targetY, bfHeight, false);

        return new double[]{targetX, targetY};
    }

    /**
     * Computes a positional GF range on a wave using a non-iterative in-field
     * escape-angle bound, similar to BeepBoop's escape-circle calculation.
     *
     * @return double[] {minGF, maxGF} where minGF <= maxGF, clamped to [-1, 1]
     */
    double[] computeActualGFRange(Wave wave,
                                  double bfWidth,
                                  double bfHeight,
                                  double refX,
                                  double refY) {
        double[] preciseGfRange = MathUtils.inFieldMaxEscapeGfRange(
                wave.originX, wave.originY, refX, refY, wave.speed, bfWidth, bfHeight);
        return normalizeGfRange(preciseGfRange[0], preciseGfRange[1]);
    }

    double[] computeApproximateGFRange(Wave wave,
                                       PhysicsUtil.PositionState state,
                                       int ticksUntilArrival,
                                       double bfWidth,
                                       double bfHeight,
                                       double refX,
                                       double refY) {
        double referenceBearing = Math.atan2(refX - wave.originX, refY - wave.originY);
        double currentBearing = Math.atan2(state.x - wave.originX, state.y - wave.originY);
        double currentDistance = Math.max(
                CIRCULAR_WAVE_APPROX_RADIUS,
                Math.hypot(state.x - wave.originX, state.y - wave.originY));
        double mea = MathUtils.maxEscapeAngle(wave.speed);
        int ticks = Math.max(1, ticksUntilArrival);

        double initialTangentialVelocity = state.velocity * FastTrig.sin(state.heading - currentBearing);
        double cwDisplacement = PathSegmentPlanner.simulateDisplacement(
                initialTangentialVelocity, 1, ticks);
        double ccwDisplacement = PathSegmentPlanner.simulateDisplacement(
                initialTangentialVelocity, -1, ticks);

        double maxCwWallDistance = maxDistanceInField(
                state.x, state.y, currentBearing + Math.PI / 2.0, bfWidth, bfHeight);
        double maxCcwWallDistance = maxDistanceInField(
                state.x, state.y, currentBearing - Math.PI / 2.0, bfWidth, bfHeight);
        cwDisplacement = Math.min(cwDisplacement, maxCwWallDistance);
        ccwDisplacement = Math.max(ccwDisplacement, -maxCcwWallDistance);

        double cwBearing = currentBearing + Math.atan2(cwDisplacement, currentDistance);
        double ccwBearing = currentBearing + Math.atan2(ccwDisplacement, currentDistance);
        double cwGF = MathUtils.angleToGf(referenceBearing, cwBearing, mea);
        double ccwGF = MathUtils.angleToGf(referenceBearing, ccwBearing, mea);

        if (!Double.isFinite(cwGF) || !Double.isFinite(ccwGF)) {
            throw new IllegalStateException(
                    "Non-finite approximate escape GF estimate: cwGF=" + cwGF + ", ccwGF=" + ccwGF);
        }

        double[] approximateRange = orderFiniteGfRange(cwGF, ccwGF);
        double[] preciseRange = MathUtils.inFieldMaxEscapeGfRange(
                wave.originX, wave.originY, refX, refY, wave.speed, bfWidth, bfHeight);
        double clippedMinGF = Math.max(approximateRange[0], preciseRange[0]);
        double clippedMaxGF = Math.min(approximateRange[1], preciseRange[1]);
        if (clippedMinGF > clippedMaxGF) {
            double gap = clippedMinGF - clippedMaxGF;
            if (gap <= GF_RANGE_OVERLAP_EPSILON) {
                double collapsedBoundary = (clippedMinGF + clippedMaxGF) * 0.5;
                clippedMinGF = collapsedBoundary;
                clippedMaxGF = collapsedBoundary;
            } else {
                // The later-wave estimate is a cheap tangential extrapolation from the
                // current radius, so it can overshoot beyond the true in-field escape
                // envelope near the extreme edges. In that case, treat the precise
                // geometric boundary as the hard limit and saturate to it.
                if (approximateRange[1] <= preciseRange[0]) {
                    clippedMinGF = preciseRange[0];
                    clippedMaxGF = preciseRange[0];
                } else if (approximateRange[0] >= preciseRange[1]) {
                    clippedMinGF = preciseRange[1];
                    clippedMaxGF = preciseRange[1];
                } else {
                    throw new IllegalStateException(
                            "Approximate and precise GF ranges do not overlap: "
                                    + "gap=" + gap + ", "
                                    + "approximate=[" + approximateRange[0] + ", " + approximateRange[1] + "], "
                                    + "precise=[" + preciseRange[0] + ", " + preciseRange[1] + "]");
                }
            }
        }

        return normalizeGfRange(clippedMinGF, clippedMaxGF);
    }

    private static double[] clampAndOrderGfRange(double firstGf, double secondGf) {
        double[] orderedRange = orderFiniteGfRange(firstGf, secondGf);
        double clampedFirstGF = Math.max(-1.0, Math.min(1.0, orderedRange[0]));
        double clampedSecondGF = Math.max(-1.0, Math.min(1.0, orderedRange[1]));
        return new double[]{clampedFirstGF, clampedSecondGF};
    }

    private static double[] orderFiniteGfRange(double firstGf, double secondGf) {
        if (!Double.isFinite(firstGf) || !Double.isFinite(secondGf)) {
            throw new IllegalStateException(
                    "Non-finite escape GF range endpoint: firstGf=" + firstGf + ", secondGf=" + secondGf);
        }
        return new double[]{
                Math.min(firstGf, secondGf),
                Math.max(firstGf, secondGf)
        };
    }

    private static double[] normalizeGfRange(double firstGf, double secondGf) {
        // Clamp each endpoint first, then order them. Clamping after ordering can
        // invert the interval when both endpoints lie outside the same bound.
        // Preserve the true reachable interval; downstream danger evaluation
        // already handles collapsed or near-collapsed GF ranges.
        return clampAndOrderGfRange(firstGf, secondGf);
    }

    static final class PathDangerMetrics {
        public final double expectedBulletDamageTaken;
        public final double expectedEnemyEnergyGain;
        public final List<PathWaveIntersection> intersections;

        PathDangerMetrics(double expectedBulletDamageTaken,
                          double expectedEnemyEnergyGain,
                          List<PathWaveIntersection> intersections) {
            this.expectedBulletDamageTaken = expectedBulletDamageTaken;
            this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
            this.intersections = intersections;
        }
    }

    static final class WaveDangerMetrics {
        public final double expectedBulletDamageTaken;
        public final double expectedEnemyEnergyGain;

        WaveDangerMetrics(double expectedBulletDamageTaken, double expectedEnemyEnergyGain) {
            this.expectedBulletDamageTaken = expectedBulletDamageTaken;
            this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
        }
    }

    static final class WaveAnalysis {
        public final WaveDangerMetrics dangerMetrics;
        public final PathWaveIntersection intersection;

        WaveAnalysis(WaveDangerMetrics dangerMetrics, PathWaveIntersection intersection) {
            this.dangerMetrics = dangerMetrics;
            this.intersection = intersection;
        }
    }

    static final class PrecomputedWaveData {
        public final double referenceBearing;
        public final double mea;
        public final List<BulletShadowUtil.WeightedGfInterval> mergedShadowGfIntervals;

        PrecomputedWaveData(double referenceBearing,
                            double mea,
                            List<BulletShadowUtil.WeightedGfInterval> mergedShadowGfIntervals) {
            this.referenceBearing = referenceBearing;
            this.mea = mea;
            this.mergedShadowGfIntervals = mergedShadowGfIntervals;
        }
    }

    CandidatePath buildCandidatePath(PhysicsUtil.Trajectory rawTrajectory, long startTime,
                                     List<Wave> scoringWaves,
                                     Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
                                     double firstTargetX, double firstTargetY,
                                     double firstWaveTargetAngle,
                                     double firstWaveSafeSpotX,
                                     double firstWaveSafeSpotY,
                                     Wave firstWave,
                                     WaveStrategy firstWaveStrategy,
                                     int firstWaveSafeSpotTick,
                                     int firstLegDurationTicks,
                                     Map<Wave, PrecomputedWaveData> precomputedWaveData) {
        return buildCandidatePath(
                rawTrajectory,
                startTime,
                scoringWaves,
                shadowCache,
                firstTargetX,
                firstTargetY,
                firstWaveTargetAngle,
                firstWaveSafeSpotX,
                firstWaveSafeSpotY,
                firstWave,
                firstWaveStrategy,
                firstWaveSafeSpotTick,
                firstLegDurationTicks,
                precomputedWaveData,
                Collections.<PathLeg>emptyList(),
                0L);
    }

    CandidatePath buildCandidatePath(PhysicsUtil.Trajectory rawTrajectory, long startTime,
                                     List<Wave> scoringWaves,
                                     Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
                                     double firstTargetX, double firstTargetY,
                                     double firstWaveTargetAngle,
                                     double firstWaveSafeSpotX,
                                     double firstWaveSafeSpotY,
                                     Wave firstWave,
                                     WaveStrategy firstWaveStrategy,
                                     int firstWaveSafeSpotTick,
                                     int firstLegDurationTicks,
                                     Map<Wave, PrecomputedWaveData> precomputedWaveData,
                                     List<PathLeg> segmentLegs,
                                     long familyId) {
        RamSimulator.Result ramResult = ramSimulator.simulate(rawTrajectory, startTime);
        PhysicsUtil.Trajectory trajectory = ramResult.adjustedTrajectory;
        PathDangerMetrics metrics = evaluatePathDangerMetrics(
                trajectory, startTime, scoringWaves, shadowCache, precomputedWaveData);
        double wallHitDamage = evaluateWallHitDamage(trajectory);
        return new CandidatePath(
                trajectory,
                startTime,
                scoringWaves,
                metrics.expectedBulletDamageTaken,
                metrics.expectedEnemyEnergyGain,
                wallHitDamage,
                ramResult.ramScoreDealt,
                ramResult.ramScoreTaken,
                ramResult.ramEnergyLoss,
                ramResult.ramEnemyEnergyLoss,
                ramResult.ramEvents,
                firstTargetX,
                firstTargetY,
                firstWaveTargetAngle,
                firstWaveSafeSpotX,
                firstWaveSafeSpotY,
                firstWave,
                firstWaveStrategy,
                firstWaveSafeSpotTick,
                firstLegDurationTicks,
                metrics.intersections,
                segmentLegs,
                familyId);
    }

    private static long fallbackFamilyId(long currentTime, int index, double targetX, double targetY) {
        long seed = 0x4F1BBCDCBFA54001L;
        seed = 31L * seed + currentTime;
        seed = 31L * seed + index;
        seed = 31L * seed + Double.doubleToLongBits(targetX);
        seed = 31L * seed + Double.doubleToLongBits(targetY);
        return seed;
    }

    /**
     * Evaluates expected bullet damage taken and expected enemy hit energy gain
     * along a trajectory from all enemy waves.
     */
    PathDangerMetrics evaluatePathDangerMetrics(PhysicsUtil.Trajectory trajectory, long startTime,
                                                List<Wave> wavesToScore,
                                                Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
                                                Map<Wave, PrecomputedWaveData> precomputedWaveData) {
        return waveIntersectionAnalyzer.evaluatePathDangerMetrics(
                trajectory, startTime, wavesToScore, shadowCache, precomputedWaveData);
    }

    private PathIntersectionContext createPathIntersectionContext(long startTime,
                                                                  PhysicsUtil.PositionState startState) {
        List<Wave> wavesToScore = getScoringWavesForState(
                startTime,
                startState.x,
                startState.y,
                startState.heading,
                startState.velocity);
        Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache =
                waveShadowCacheBuilder.buildBaseShadowCache(wavesToScore);
        return new PathIntersectionContext(startTime, startState.x, startState.y, wavesToScore, shadowCache);
    }

    public List<PathWaveIntersection> collectPathWaveIntersections(CandidatePath path,
                                                                   PathIntersectionContext context) {
        if (path == null || path.trajectory == null || path.trajectory.length() == 0) {
            return new ArrayList<>();
        }
        PhysicsUtil.PositionState startState = path.trajectory.stateAt(0);
        PathIntersectionContext activeContext = context;
        if (activeContext == null) {
            activeContext = createPathIntersectionContext(path.startTime, startState);
        } else if (activeContext.startTime != path.startTime
                || Double.doubleToLongBits(activeContext.startX) != Double.doubleToLongBits(startState.x)
                || Double.doubleToLongBits(activeContext.startY) != Double.doubleToLongBits(startState.y)) {
            throw new IllegalStateException(
                    "PathIntersectionContext does not match path start state/time");
        }
        if (path.pathIntersections != null) {
            return path.pathIntersections;
        }
        return waveIntersectionAnalyzer.collectPathWaveIntersections(path, activeContext);
    }

    private double evaluateWallHitDamage(PhysicsUtil.Trajectory trajectory) {
        double totalWallHitDamage = 0.0;
        PhysicsUtil.PositionState[] states = trajectory.states;
        for (int i = 1; i < states.length; i++) {
            totalWallHitDamage += states[i].wallHitDamage;
        }
        return totalWallHitDamage;
    }

    static double maxDistanceInField(double x, double y, double angle, double bfWidth, double bfHeight) {
        double margin = PhysicsUtil.WALL_MARGIN;
        double minX = margin;
        double maxX = bfWidth - margin;
        double minY = margin;
        double maxY = bfHeight - margin;

        double dirX = FastTrig.sin(angle);
        double dirY = FastTrig.cos(angle);

        double tX = Double.POSITIVE_INFINITY;
        if (dirX > 1e-9) {
            tX = (maxX - x) / dirX;
        } else if (dirX < -1e-9) {
            tX = (minX - x) / dirX;
        }

        double tY = Double.POSITIVE_INFINITY;
        if (dirY > 1e-9) {
            tY = (maxY - y) / dirY;
        } else if (dirY < -1e-9) {
            tY = (minY - y) / dirY;
        }

        double maxDist = Math.min(tX, tY);
        return Double.isFinite(maxDist) ? Math.max(0.0, maxDist) : 0.0;
    }

    static double maxRadiusOnAngleWithinField(Wave wave, double angle, double bfWidth, double bfHeight) {
        return maxDistanceInField(wave.originX, wave.originY, angle, bfWidth, bfHeight);
    }

    static double clampToField(double value, double fieldSize, boolean isX) {
        double margin = PhysicsUtil.WALL_MARGIN;
        return Math.max(margin, Math.min(fieldSize - margin, value));
    }

    static double[][] buildPrefireTargets(double centerX, double centerY, double bearingFromOpponent) {
        double tangentCwX = FastTrig.cos(bearingFromOpponent);
        double tangentCwY = -FastTrig.sin(bearingFromOpponent);
        double tangentCcwX = -tangentCwX;
        double tangentCcwY = -tangentCwY;
        double radialOutX = FastTrig.sin(bearingFromOpponent);
        double radialOutY = FastTrig.cos(bearingFromOpponent);
        double radialInX = -radialOutX;
        double radialInY = -radialOutY;

        return new double[][]{
                {centerX + tangentCwX * PRE_FIRE_TARGET_DISTANCE, centerY + tangentCwY * PRE_FIRE_TARGET_DISTANCE},   // max CW
                {centerX + tangentCcwX * PRE_FIRE_TARGET_DISTANCE, centerY + tangentCcwY * PRE_FIRE_TARGET_DISTANCE}, // max CCW
                {centerX + radialInX * PRE_FIRE_TARGET_DISTANCE, centerY + radialInY * PRE_FIRE_TARGET_DISTANCE},     // close
                {centerX + radialOutX * PRE_FIRE_TARGET_DISTANCE, centerY + radialOutY * PRE_FIRE_TARGET_DISTANCE}    // far
        };
    }

    static List<Wave> buildPlanningWavesForState(List<Wave> scoringWaves,
                                                 double x,
                                                 double y,
                                                 long currentTime) {
        return MovementWavePlanner.buildPlanningWavesForState(scoringWaves, x, y, currentTime);
    }

    void addCandidatePathFromStates(List<CandidatePath> results,
                                    List<PhysicsUtil.PositionState> pathStates,
                                    long startTime,
                                    List<Wave> scoringWaves,
                                    double firstTargetX,
                                    double firstTargetY,
                                    int firstSegmentEndTick) {
        PhysicsUtil.PositionState[] statesArray = pathStates.toArray(new PhysicsUtil.PositionState[0]);
        PhysicsUtil.Trajectory trajectory = new PhysicsUtil.Trajectory(statesArray);
        Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache =
                waveShadowCacheBuilder.buildBaseShadowCache(scoringWaves);
        Map<Wave, PrecomputedWaveData> precomputedWaveData =
                waveShadowCacheBuilder.buildPrecomputedWaveData(scoringWaves, shadowCache);
        results.add(buildCandidatePath(
                trajectory, startTime, scoringWaves, shadowCache,
                firstTargetX,
                firstTargetY,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                null,
                WaveStrategy.NONE,
                firstSegmentEndTick,
                firstSegmentEndTick,
                precomputedWaveData));
    }

    @Override
    public SurfSegmentRecommendation recommendFutureSurfSegment(PhysicsUtil.Trajectory committedTrajectory,
                                                                long trajectoryStartTime,
                                                                PredictedOpponentState opponentStart,
                                                                OpponentDriveSimulator.Instruction opponentInstruction) {
        if (committedTrajectory == null || committedTrajectory.length() < 1) {
            throw new IllegalArgumentException("Future surf recommendation requires a non-empty committed trajectory");
        }
        if (opponentStart != null && opponentInstruction == null) {
            throw new IllegalArgumentException(
                    "Future surf recommendation requires a non-null opponent instruction when opponent state is present");
        }

        FuturePredictionState futureState =
                simulateFuturePredictionState(committedTrajectory, trajectoryStartTime, opponentStart, opponentInstruction);
        List<Wave> scoringWaves = selectFutureScoringWaves(
                futureState.activeEnemyWaves,
                futureState.ourState.x,
                futureState.ourState.y,
                futureState.time);
        List<Wave> planningWaves = buildPlanningWavesForState(
                scoringWaves,
                futureState.ourState.x,
                futureState.ourState.y,
                futureState.time);
        if (planningWaves.isEmpty()) {
            if (opponentInstruction == null) {
                return null;
            }
            return wavePathGenerator.recommendFutureSurfSegment(
                    futureState.ourState,
                    futureState.time,
                    getBattlefieldWidth(),
                    getBattlefieldHeight(),
                    scoringWaves,
                    futureState.opponentState,
                    opponentInstruction);
        }

        Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache =
                waveShadowCacheBuilder.buildBaseShadowCache(scoringWaves);
        PathGenerationContext context = new PathGenerationContext(
                futureState.ourState,
                futureState.time,
                getBattlefieldWidth(),
                getBattlefieldHeight(),
                scoringWaves,
                null,
                futureState.opponentState != null ? futureState.opponentState.x : Double.NaN,
                futureState.opponentState != null ? futureState.opponentState.y : Double.NaN,
                scoringWaves,
                planningWaves,
                shadowCache);
        Map<Wave, PrecomputedWaveData> precomputedWaveData =
                waveShadowCacheBuilder.buildPrecomputedWaveData(scoringWaves, shadowCache);
        return recommendationFromCandidates(
                wavePathGenerator.generateCandidatePaths(context, shadowCache, precomputedWaveData));
    }

    private List<Wave> selectFutureScoringWaves(List<Wave> activeEnemyWaves,
                                                double robotX,
                                                double robotY,
                                                long currentTime) {
        List<Wave> scoringWaves = new ArrayList<>(activeEnemyWaves);
        scoringWaves.sort(Comparator.comparingInt(
                w -> PhysicsUtil.waveArrivalTick(w, robotX, robotY, currentTime)));
        if (scoringWaves.size() > MAX_WAVE_DEPTH) {
            return new ArrayList<>(scoringWaves.subList(0, MAX_WAVE_DEPTH));
        }
        return scoringWaves;
    }

    SurfSegmentRecommendation recommendationFromCandidates(List<CandidatePath> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        CandidatePath best = null;
        for (CandidatePath candidate : candidates) {
            if (candidate == null || candidate.firstLegDurationTicks <= 0
                    || !Double.isFinite(candidate.firstTargetX) || !Double.isFinite(candidate.firstTargetY)) {
                continue;
            }
            if (best == null
                    || candidate.totalDanger < best.totalDanger - 1e-9
                    || (Math.abs(candidate.totalDanger - best.totalDanger) <= 1e-9
                    && candidate.wallHitDamage < best.wallHitDamage - 1e-9)
                    || (Math.abs(candidate.totalDanger - best.totalDanger) <= 1e-9
                    && Math.abs(candidate.wallHitDamage - best.wallHitDamage) <= 1e-9
                    && candidate.firstLegDurationTicks < best.firstLegDurationTicks)) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        return new SurfSegmentRecommendation(
                best.firstTargetX,
                best.firstTargetY,
                best.firstLegDurationTicks,
                best.totalDanger);
    }

    private FuturePredictionState simulateFuturePredictionState(PhysicsUtil.Trajectory committedTrajectory,
                                                                long trajectoryStartTime,
                                                                PredictedOpponentState opponentStart,
                                                                OpponentDriveSimulator.Instruction opponentInstruction) {
        PhysicsUtil.PositionState[] ourStates = committedTrajectory.states;
        PhysicsUtil.PositionState opponentState = opponentStart != null ? opponentStart.toPositionState() : null;
        double opponentGunHeat = opponentStart != null ? Math.max(0.0, opponentStart.gunHeat) : 0.0;
        double opponentEnergy = opponentStart != null ? Math.max(0.0, opponentStart.energy) : 0.0;
        double opponentLastDetectedBulletPower = opponentStart != null ? opponentStart.lastDetectedBulletPower : Double.NaN;
        List<Wave> predictedEnemyWaves = new ArrayList<>();
        double bfWidth = getBattlefieldWidth();
        double bfHeight = getBattlefieldHeight();
        double[] opponentMoveInstruction = new double[2];
        for (int tick = 0; tick < ourStates.length; tick++) {
            long time = trajectoryStartTime + tick;
            PhysicsUtil.PositionState ourState = ourStates[tick];
            if (opponentState != null && opponentEnergy >= 0.1 && opponentGunHeat <= 1e-9) {
                double firePower = Math.min(3.0, opponentEnergy);
                MotionContext motionContext = deriveMotionContext(ourStates, tick, bfWidth, bfHeight);
                List<Wave> referenceWaves = new ArrayList<>(info.getEnemyWaves());
                referenceWaves.addAll(predictedEnemyWaves);
                int lastNonZeroLateralDirectionSign = deriveLastNonZeroLateralDirectionSign(
                        ourStates,
                        tick,
                        opponentState.x,
                        opponentState.y);
                predictedEnemyWaves.add(createVirtualEnemyWave(
                        opponentState.x,
                        opponentState.y,
                        firePower,
                        time,
                        ourState.x,
                        ourState.y,
                        ourState.heading,
                        ourState.velocity,
                        motionContext.accelerationSign,
                        motionContext.ticksSinceVelocityReversal,
                        motionContext.ticksSinceDecel,
                        lastNonZeroLateralDirectionSign,
                        bfWidth,
                        bfHeight,
                        referenceWaves));
                opponentEnergy -= firePower;
                opponentGunHeat = 1.0 + firePower / 5.0;
                opponentLastDetectedBulletPower = firePower;
            }
            if (tick >= ourStates.length - 1) {
                break;
            }
            if (opponentState != null) {
                if (opponentInstruction instanceof ReactiveOpponentPredictor) {
                    opponentState = ((ReactiveOpponentPredictor) opponentInstruction).predictNextState(
                            tick,
                            ourState,
                            opponentState,
                            bfWidth,
                            bfHeight);
                } else {
                    OpponentDriveSimulator.DriveTarget target = opponentInstruction.targetForTick(
                            tick,
                            ourState,
                            opponentState,
                            bfWidth,
                            bfHeight);
                    opponentState = PhysicsUtil.advanceTowardTarget(
                            opponentState,
                            target.x,
                            target.y,
                            bfWidth,
                            bfHeight,
                            opponentMoveInstruction);
                }
                opponentGunHeat = Math.max(0.0, opponentGunHeat - 0.1);
            }
        }

        PhysicsUtil.PositionState finalOurState = ourStates[ourStates.length - 1];
        long finalTime = trajectoryStartTime + ourStates.length - 1L;
        List<Wave> activeEnemyWaves = new ArrayList<>();
        for (Wave wave : info.getEnemyWaves()) {
            if (!wave.hasPassed(finalOurState.x, finalOurState.y, finalTime)) {
                activeEnemyWaves.add(wave);
            }
        }
        for (Wave wave : predictedEnemyWaves) {
            if (!wave.hasPassed(finalOurState.x, finalOurState.y, finalTime)) {
                activeEnemyWaves.add(wave);
            }
        }
        return new FuturePredictionState(
                finalOurState,
                finalTime,
                activeEnemyWaves,
                opponentState == null
                        ? null
                        : new PredictedOpponentState(
                                opponentState.x,
                                opponentState.y,
                                opponentState.heading,
                                opponentState.velocity,
                                opponentGunHeat,
                                opponentEnergy,
                                opponentLastDetectedBulletPower));
    }

    private static final class FuturePredictionState {
        final PhysicsUtil.PositionState ourState;
        final long time;
        final List<Wave> activeEnemyWaves;
        final PredictedOpponentState opponentState;

        FuturePredictionState(PhysicsUtil.PositionState ourState,
                              long time,
                              List<Wave> activeEnemyWaves,
                              PredictedOpponentState opponentState) {
            this.ourState = ourState;
            this.time = time;
            this.activeEnemyWaves = activeEnemyWaves;
            this.opponentState = opponentState;
        }
    }

    Wave createVirtualEnemyWave(double originX,
                                double originY,
                                double firePower,
                                long fireTime,
                                double targetX,
                                double targetY,
                                double targetHeading,
                                double targetVelocity,
                                int targetAccelerationSign,
                                int targetTicksSinceVelocityReversal,
                                int targetTicksSinceDecel,
                                int targetLastNonZeroLateralDirectionSign,
                                double bfWidth,
                                double bfHeight,
                                List<Wave> referenceWaves) {
        return movementWavePlanner.createVirtualEnemyWave(
                originX,
                originY,
                firePower,
                fireTime,
                targetX,
                targetY,
                targetHeading,
                targetVelocity,
                targetAccelerationSign,
                targetTicksSinceVelocityReversal,
                targetTicksSinceDecel,
                targetLastNonZeroLateralDirectionSign,
                bfWidth,
                bfHeight,
                referenceWaves);
    }

    double waveReferenceX(Wave wave) {
        requireWaveReference(wave);
        return wave.targetX;
    }

    double waveReferenceY(Wave wave) {
        requireWaveReference(wave);
        return wave.targetY;
    }

    private static void requireWaveReference(Wave wave) {
        if (Double.isNaN(wave.targetX) || Double.isNaN(wave.targetY)) {
            throw new IllegalStateException("Enemy wave missing fire-time target reference");
        }
    }

    public List<Wave> getScoringWavesForState(long currentTime,
                                              double robotX,
                                              double robotY,
                                              double robotHeading,
                                              double robotVelocity) {
        return movementWavePlanner.getScoringWavesForState(
                currentTime, robotX, robotY, robotHeading, robotVelocity);
    }

    @Override
    public List<Wave> getScoringWavesForPathState(CandidatePath path, int tickOffset) {
        if (path == null || path.trajectory == null || path.trajectory.length() == 0) {
            throw new IllegalArgumentException("Path-state scoring waves require a non-empty candidate path");
        }
        if (tickOffset < 0 || tickOffset >= path.trajectory.length()) {
            throw new IllegalArgumentException("Path-state scoring waves require a valid tick offset");
        }
        PhysicsUtil.PositionState fireState = path.trajectory.stateAt(tickOffset);
        MotionContext motionContext = deriveMotionContext(
                path.trajectory.states,
                tickOffset,
                getBattlefieldWidth(),
                getBattlefieldHeight());
        return movementWavePlanner.getScoringWavesForState(
                path.startTime + tickOffset,
                fireState.x,
                fireState.y,
                fireState.heading,
                fireState.velocity,
                motionContext);
    }

    static List<BulletShadowUtil.ShadowInterval> shadowCacheForWave(
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache, Wave wave) {
        // The base shadow cache must be built from the same wave list as the downstream planner
        // caches. Real enemy waves always have an entry; virtual waves are allowed only in the
        // base cache and must never be queried through the precomputed-wave path.
        List<BulletShadowUtil.ShadowInterval> shadows = shadowCache.get(wave);
        if (shadows == null) {
            throw new IllegalStateException("Missing shadow cache for enemy wave");
        }
        return shadows;
    }

    double getBattlefieldWidth() {
        return info.getBattlefieldWidth();
    }

    double getBattlefieldHeight() {
        return info.getBattlefieldHeight();
    }

    Map<Wave, List<BulletShadowUtil.ShadowInterval>> buildBaseShadowCacheForPlanning(
            List<Wave> waves) {
        return waveShadowCacheBuilder.buildBaseShadowCache(waves);
    }

    Map<Wave, PrecomputedWaveData> buildPrecomputedWaveDataForPlanning(
            List<Wave> waves,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache) {
        return waveShadowCacheBuilder.buildPrecomputedWaveData(waves, shadowCache);
    }

    static long estimateVirtualFireTime(long currentTime, double opponentGunHeat) {
        return MovementWavePlanner.estimateVirtualFireTime(currentTime, opponentGunHeat);
    }
}




