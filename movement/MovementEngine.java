package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import robocode.Rules;
import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
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
    static final double CIRCULAR_WAVE_APPROX_RADIUS = RobotHitbox.HALF_WIDTH;
    private static final double GF_RANGE_OVERLAP_EPSILON = 1e-5;
    private static final int DISTRIBUTION_CACHE_CAPACITY = 24;
    private static final Random random = new Random();

    private final Info info;
    private final RamSimulator ramSimulator;
    private final WaveIntersectionAnalyzer waveIntersectionAnalyzer;
    private final WaveShadowCacheBuilder waveShadowCacheBuilder;
    private final PathGenerator pathGenerator;
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

            PathLeg leg = new PathLeg(targetX, targetY, 1, PhysicsUtil.SteeringMode.DIRECT);
            PhysicsUtil.Trajectory traj = PhysicsUtil.simulateTrajectory(
                    startState,
                    leg.targetX,
                    leg.targetY,
                    1,
                    leg.steeringMode,
                    bfWidth,
                    bfHeight);
            results.add(buildCandidatePath(
                    traj, currentTime, scoringWaves, shadowCache,
                    targetX, targetY,
                    Double.NaN, Double.NaN, Double.NaN,
                    null, -1, 1, precomputedWaveData,
                    Collections.singletonList(leg),
                    fallbackFamilyId(currentTime, i, targetX, targetY)));
        }
    }

    static final class MotionContext {
        public final double headingDelta;
        public final double velocityDelta;
        public final int accelerationSign;
        public final int ticksSinceVelocityReversal;
        public final int ticksSinceDecel;
        public final int lastNonZeroVelocitySign;
        public final double distanceLast10;
        public final double distanceLast20;

        MotionContext(double headingDelta,
                      double velocityDelta,
                      int accelerationSign,
                      int ticksSinceVelocityReversal,
                      int ticksSinceDecel,
                      int lastNonZeroVelocitySign,
                      double distanceLast10,
                      double distanceLast20) {
            this.headingDelta = headingDelta;
            this.velocityDelta = velocityDelta;
            this.accelerationSign = accelerationSign;
            this.ticksSinceVelocityReversal = ticksSinceVelocityReversal;
            this.ticksSinceDecel = ticksSinceDecel;
            this.lastNonZeroVelocitySign = lastNonZeroVelocitySign;
            this.distanceLast10 = distanceLast10;
            this.distanceLast20 = distanceLast20;
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

    static double deriveHeadingDelta(PhysicsUtil.PositionState[] states, int tickOffset) {
        return deriveHeadingDelta(states.length, index -> states[index], tickOffset);
    }

    static double deriveHeadingDelta(List<PhysicsUtil.PositionState> states, int tickOffset) {
        return deriveHeadingDelta(states.size(), states::get, tickOffset);
    }

    private static double deriveHeadingDelta(int stateCount,
                                             java.util.function.IntFunction<PhysicsUtil.PositionState> stateAt,
                                             int tickOffset) {
        int boundedTickOffset = Math.max(0, Math.min(tickOffset, stateCount - 1));
        if (boundedTickOffset <= 0) {
            return 0.0;
        }
        return MathUtils.normalizeAngle(
                stateAt.apply(boundedTickOffset).heading - stateAt.apply(boundedTickOffset - 1).heading);
    }

    static double deriveVelocityDelta(PhysicsUtil.PositionState[] states, int tickOffset) {
        return deriveVelocityDelta(states.length, index -> states[index], tickOffset);
    }

    static double deriveVelocityDelta(List<PhysicsUtil.PositionState> states, int tickOffset) {
        return deriveVelocityDelta(states.size(), states::get, tickOffset);
    }

    private static double deriveVelocityDelta(int stateCount,
                                              java.util.function.IntFunction<PhysicsUtil.PositionState> stateAt,
                                              int tickOffset) {
        int boundedTickOffset = Math.max(0, Math.min(tickOffset, stateCount - 1));
        if (boundedTickOffset <= 0) {
            return 0.0;
        }
        return stateAt.apply(boundedTickOffset).velocity - stateAt.apply(boundedTickOffset - 1).velocity;
    }

    static double deriveDistanceLastTicks(PhysicsUtil.PositionState[] states,
                                          int tickOffset,
                                          int tickCount) {
        return deriveDistanceLastTicks(states.length, index -> states[index], tickOffset, tickCount);
    }

    static double deriveDistanceLastTicks(List<PhysicsUtil.PositionState> states,
                                          int tickOffset,
                                          int tickCount) {
        return deriveDistanceLastTicks(states.size(), states::get, tickOffset, tickCount);
    }

    private static double deriveDistanceLastTicks(int stateCount,
                                                  java.util.function.IntFunction<PhysicsUtil.PositionState> stateAt,
                                                  int tickOffset,
                                                  int tickCount) {
        if (tickCount <= 0 || stateCount <= 0) {
            return 0.0;
        }
        int boundedTickOffset = Math.max(0, Math.min(tickOffset, stateCount - 1));
        int startIndex = Math.max(0, boundedTickOffset - tickCount + 1);
        double distance = 0.0;
        for (int i = startIndex; i <= boundedTickOffset; i++) {
            distance += Math.abs(stateAt.apply(i).velocity);
        }
        return distance;
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
        double headingDelta = deriveHeadingDelta(stateCount, stateAt, boundedTickOffset);
        double velocityDelta = deriveVelocityDelta(stateCount, stateAt, boundedTickOffset);

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
        double distanceLast10 = deriveDistanceLastTicks(stateCount, stateAt, boundedTickOffset, 10);
        double distanceLast20 = deriveDistanceLastTicks(stateCount, stateAt, boundedTickOffset, 20);

        return new MotionContext(
                headingDelta,
                velocityDelta,
                accelerationSign,
                ticksSinceVelocityReversal,
                ticksSinceDecel,
                lastNonZeroVelocitySign,
                distanceLast10,
                distanceLast20);
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

    static double deriveMomentumLateralVelocity(PhysicsUtil.PositionState[] states,
                                                int tickOffset,
                                                double sourceX,
                                                double sourceY) {
        return deriveMomentumState(states.length, index -> states[index], tickOffset, sourceX, sourceY)
                .momentumLateralVelocity;
    }

    static int deriveMomentumDirectionSign(PhysicsUtil.PositionState[] states,
                                           int tickOffset,
                                           double sourceX,
                                           double sourceY) {
        return deriveMomentumState(states.length, index -> states[index], tickOffset, sourceX, sourceY)
                .momentumDirectionSign;
    }

    private static DerivedMomentumState deriveMomentumState(int stateCount,
                                                            java.util.function.IntFunction<PhysicsUtil.PositionState> stateAt,
                                                            int tickOffset,
                                                            double sourceX,
                                                            double sourceY) {
        int boundedTickOffset = Math.max(0, Math.min(tickOffset, stateCount - 1));
        int startIndex = Math.max(0, boundedTickOffset - 12);
        double momentumLateralVelocity = 0.0;
        int momentumDirectionSign = 0;
        boolean initialized = false;
        for (int i = startIndex; i <= boundedTickOffset; i++) {
            PhysicsUtil.PositionState state = stateAt.apply(i);
            double lateralVelocity = WaveContextFeatures.computeLateralVelocity(
                    sourceX,
                    sourceY,
                    state.x,
                    state.y,
                    state.heading,
                    state.velocity);
            momentumLateralVelocity = initialized
                    ? WaveContextFeatures.updateMomentumLateralVelocity(momentumLateralVelocity, lateralVelocity)
                    : lateralVelocity;
            int lateralDirectionSign = WaveContextFeatures.computeLateralDirectionSign(
                    sourceX,
                    sourceY,
                    state.x,
                    state.y,
                    state.heading,
                    state.velocity);
            momentumDirectionSign = WaveContextFeatures.resolveMomentumDirectionSign(
                    momentumLateralVelocity,
                    momentumDirectionSign,
                    lateralDirectionSign);
            initialized = true;
        }
        return new DerivedMomentumState(momentumLateralVelocity, momentumDirectionSign);
    }

    private static final class DerivedMomentumState {
        final double momentumLateralVelocity;
        final int momentumDirectionSign;

        DerivedMomentumState(double momentumLateralVelocity, int momentumDirectionSign) {
            this.momentumLateralVelocity = momentumLateralVelocity;
            this.momentumDirectionSign = momentumDirectionSign;
        }
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
        double cwDisplacement = TangentialDisplacement.simulate(
                initialTangentialVelocity, 1, ticks);
        double ccwDisplacement = TangentialDisplacement.simulate(
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

    PathDangerMetrics evaluatePathDangerMetrics(PhysicsUtil.Trajectory trajectory,
                                                long startTime,
                                                List<Wave> wavesToScore,
                                                Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
                                                Map<Wave, PrecomputedWaveData> precomputedWaveData,
                                                int startStateIndex,
                                                int endStateIndex,
                                                boolean includeStationaryTail) {
        return waveIntersectionAnalyzer.evaluatePathDangerMetrics(
                trajectory,
                startTime,
                wavesToScore,
                shadowCache,
                precomputedWaveData,
                startStateIndex,
                endStateIndex,
                includeStationaryTail);
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

    static double clampToField(double value, double fieldSize, boolean isX) {
        double margin = PhysicsUtil.WALL_MARGIN;
        return Math.max(margin, Math.min(fieldSize - margin, value));
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
                firstSegmentEndTick,
                firstSegmentEndTick,
                precomputedWaveData));
    }

    private List<Wave> selectFutureScoringWaves(List<Wave> activeEnemyWaves,
                                                double robotX,
                                                double robotY,
                                                long currentTime) {
        List<Wave> scoringWaves = new ArrayList<>(activeEnemyWaves);
        scoringWaves.sort(Comparator.comparingInt(
                w -> PhysicsUtil.waveArrivalTick(w, robotX, robotY, currentTime)));
        if (scoringWaves.size() > BotConfig.Movement.MAX_WAVE_DEPTH) {
            return new ArrayList<>(scoringWaves.subList(0, BotConfig.Movement.MAX_WAVE_DEPTH));
        }
        return scoringWaves;
    }

    private FuturePredictionState simulateFuturePredictionState(PhysicsUtil.Trajectory committedTrajectory,
                                                                long trajectoryStartTime,
                                                                PredictedOpponentState opponentStart,
                                                                OpponentDriveSimulator.Instruction opponentInstruction) {
        PhysicsUtil.PositionState[] ourStates = committedTrajectory.states;
        return simulateFuturePredictionState(
                ourStates,
                trajectoryStartTime,
                opponentStart,
                opponentInstruction,
                java.util.Collections.<Wave>emptyList(),
                0,
                true);
    }

    private FuturePredictionState continueFuturePredictionState(PhysicsUtil.PositionState[] fullStates,
                                                                long trajectoryStartTime,
                                                                int startTickOffset,
                                                                FuturePredictionState prefixFuture,
                                                                OpponentDriveSimulator.Instruction opponentInstruction) {
        return simulateFuturePredictionState(
                fullStates,
                trajectoryStartTime,
                prefixFuture.opponentState,
                opponentInstruction,
                prefixFuture.allPredictedWaves,
                startTickOffset,
                false);
    }

    private FuturePredictionState simulateFuturePredictionState(PhysicsUtil.PositionState[] ourStates,
                                                                long trajectoryStartTime,
                                                                PredictedOpponentState opponentStart,
                                                                OpponentDriveSimulator.Instruction opponentInstruction,
                                                                List<Wave> initialPredictedWaves,
                                                                int startTickOffset,
                                                                boolean fireOnStartState) {
        PhysicsUtil.PositionState opponentState = opponentStart != null ? opponentStart.toPositionState() : null;
        double opponentGunHeat = opponentStart != null ? Math.max(0.0, opponentStart.gunHeat) : 0.0;
        double opponentEnergy = opponentStart != null ? Math.max(0.0, opponentStart.energy) : 0.0;
        double opponentLastDetectedBulletPower = opponentStart != null ? opponentStart.lastDetectedBulletPower : Double.NaN;
        List<Wave> predictedEnemyWaves = new ArrayList<>(initialPredictedWaves);
        double bfWidth = getBattlefieldWidth();
        double bfHeight = getBattlefieldHeight();
        double[] opponentMoveInstruction = new double[2];
        PhysicsUtil.PositionState[] rawOurStates = null;
        boolean ourPathAdjusted = false;
        boolean ourTurnLocked = false;
        boolean opponentTurnLocked = false;
        double[] ourMoveInstruction = new double[2];
        for (int tick = startTickOffset; tick < ourStates.length; tick++) {
            long time = trajectoryStartTime + tick;
            PhysicsUtil.PositionState ourState = ourStates[tick];
            if ((tick > startTickOffset || fireOnStartState)
                    && opponentState != null
                    && opponentEnergy >= 0.1
                    && opponentGunHeat <= 1e-9) {
                double firePower = Double.isNaN(opponentLastDetectedBulletPower)
                        ? Math.min(3.0, opponentEnergy)
                        : Math.min(opponentLastDetectedBulletPower, opponentEnergy);
                MotionContext motionContext = deriveMotionContext(ourStates, tick, bfWidth, bfHeight);
                List<Wave> referenceWaves = new ArrayList<>(info.getEnemyWaves());
                referenceWaves.addAll(predictedEnemyWaves);
                int lastNonZeroLateralDirectionSign = deriveLastNonZeroLateralDirectionSign(
                        ourStates,
                        tick,
                        opponentState.x,
                        opponentState.y);
                double momentumLateralVelocity = deriveMomentumLateralVelocity(
                        ourStates,
                        tick,
                        opponentState.x,
                        opponentState.y);
                int momentumDirectionSign = deriveMomentumDirectionSign(
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
                        motionContext.headingDelta,
                        motionContext.velocityDelta,
                        motionContext.accelerationSign,
                        motionContext.ticksSinceVelocityReversal,
                        motionContext.ticksSinceDecel,
                        motionContext.distanceLast10,
                        motionContext.distanceLast20,
                        didLastEnemyWaveHitRobot(),
                        lastNonZeroLateralDirectionSign,
                        momentumLateralVelocity,
                        momentumDirectionSign,
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

            // If our path was adjusted by a previous collision, re-simulate our next tick
            // position toward the original planned target.
            if (ourPathAdjusted) {
                ourStates[tick + 1] = RamCollisionUtil.advanceTowardPlannedState(
                        ourState, rawOurStates[tick + 1], ourTurnLocked,
                        ourMoveInstruction, bfWidth, bfHeight);
            }
            PhysicsUtil.PositionState nextOurState = ourStates[tick + 1];
            ourTurnLocked = false;

            PhysicsUtil.PositionState opponentAtTick = opponentState;

            // Phase 1: Did our movement to tick+1 collide with opponent at tick?
            if (opponentState != null
                    && RamCollisionUtil.robotsOverlap(nextOurState.x, nextOurState.y,
                            opponentState.x, opponentState.y)
                    && RamCollisionUtil.isCollisionFault(nextOurState.velocity, nextOurState.heading,
                            nextOurState.x, nextOurState.y, opponentState.x, opponentState.y)) {
                if (!ourPathAdjusted) {
                    rawOurStates = java.util.Arrays.copyOf(ourStates, ourStates.length);
                    ourPathAdjusted = true;
                }
                ourStates[tick + 1] = RamCollisionUtil.rollbackCollision(ourState, nextOurState);
                nextOurState = ourStates[tick + 1];
                ourTurnLocked = true;
            }

            // Advance opponent to tick+1, respecting turn lock from a previous collision.
            if (opponentState != null) {
                if (opponentTurnLocked) {
                    OpponentDriveSimulator.DriveTarget target = opponentInstruction.targetForTick(
                            tick, ourState, opponentAtTick, bfWidth, bfHeight);
                    PhysicsUtil.computeMovementInstructionInto(
                            opponentAtTick.x, opponentAtTick.y,
                            opponentAtTick.heading, opponentAtTick.velocity,
                            target.x, target.y, opponentMoveInstruction);
                    opponentMoveInstruction[1] = 0.0;
                    opponentState = PhysicsUtil.calculateNextTick(
                            opponentAtTick.x, opponentAtTick.y,
                            opponentAtTick.heading, opponentAtTick.velocity,
                            opponentMoveInstruction[0], opponentMoveInstruction[1],
                            bfWidth, bfHeight);
                } else if (opponentInstruction instanceof ReactiveOpponentPredictor) {
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
            opponentTurnLocked = false;

            // Phase 2: Did opponent's movement to tick+1 collide with us at tick+1?
            if (opponentState != null
                    && RamCollisionUtil.robotsOverlap(nextOurState.x, nextOurState.y,
                            opponentState.x, opponentState.y)
                    && RamCollisionUtil.isCollisionFault(opponentState.velocity, opponentState.heading,
                            opponentState.x, opponentState.y, nextOurState.x, nextOurState.y)) {
                opponentState = new PhysicsUtil.PositionState(
                        opponentAtTick.x, opponentAtTick.y,
                        opponentState.heading, 0.0);
                opponentTurnLocked = true;
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
                predictedEnemyWaves,
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
        final List<Wave> allPredictedWaves;
        final PredictedOpponentState opponentState;

        FuturePredictionState(PhysicsUtil.PositionState ourState,
                              long time,
                              List<Wave> activeEnemyWaves,
                              List<Wave> allPredictedWaves,
                              PredictedOpponentState opponentState) {
            this.ourState = ourState;
            this.time = time;
            this.activeEnemyWaves = activeEnemyWaves;
            this.allPredictedWaves = allPredictedWaves;
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
                                double targetHeadingDelta,
                                double targetVelocityDelta,
                                int targetAccelerationSign,
                                int targetTicksSinceVelocityReversal,
                                int targetTicksSinceDecel,
                                double targetDistanceLast10,
                                double targetDistanceLast20,
                                boolean targetDidHit,
                                int targetLastNonZeroLateralDirectionSign,
                                double targetMomentumLateralVelocity,
                                int targetMomentumDirectionSign,
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
                targetHeadingDelta,
                targetVelocityDelta,
                targetAccelerationSign,
                targetTicksSinceVelocityReversal,
                targetTicksSinceDecel,
                targetDistanceLast10,
                targetDistanceLast20,
                targetDidHit,
                targetLastNonZeroLateralDirectionSign,
                targetMomentumLateralVelocity,
                targetMomentumDirectionSign,
                bfWidth,
                bfHeight,
                referenceWaves);
    }

    boolean didLastEnemyWaveHitRobot() {
        return info.didLastEnemyWaveHitRobot();
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

    @Override
    public List<PathLeg> generateBestRandomTail(PhysicsUtil.Trajectory committedPrefix,
                                                 long prefixStartTime,
                                                 PredictedOpponentState opponentStart,
                                                 OpponentDriveSimulator.Instruction opponentInstruction,
                                                 int minTailTicks,
                                                 List<PathLeg> carryForwardTail) {
        if (committedPrefix == null || committedPrefix.length() < 1) {
            throw new IllegalArgumentException("generateBestRandomTail requires a non-empty committed prefix");
        }
        if (opponentStart != null && opponentInstruction == null) {
            throw new IllegalArgumentException(
                    "generateBestRandomTail requires a non-null opponent instruction when opponent state is present");
        }

        boolean hasCarryForward = carryForwardTail != null && !carryForwardTail.isEmpty();
        int candidateCount = BotConfig.Movement.RANDOM_TAIL_CANDIDATE_COUNT;
        double bfWidth = getBattlefieldWidth();
        double bfHeight = getBattlefieldHeight();

        // Clone prefix states so collision adjustments don't mutate the shared Trajectory.
        PhysicsUtil.PositionState[] prefixStates = committedPrefix.states.clone();
        int prefixLength = prefixStates.length;
        long prefixEndTime = prefixStartTime + prefixLength - 1L;

        // Simulate opponent along committed prefix (with collision handling) to get future state.
        FuturePredictionState prefixFuture = simulateFuturePredictionState(
                prefixStates, prefixStartTime, opponentStart, opponentInstruction,
                java.util.Collections.<Wave>emptyList(), 0, true);
        PhysicsUtil.PositionState prefixEndState = prefixStates[prefixLength - 1];

        // Build planning waves from the (collision-adjusted) prefix end state
        List<Wave> baseScoringWaves = selectFutureScoringWaves(
                prefixFuture.activeEnemyWaves,
                prefixEndState.x,
                prefixEndState.y,
                prefixEndTime);
        List<Wave> planningWaves = buildPlanningWavesForState(
                baseScoringWaves,
                prefixEndState.x,
                prefixEndState.y,
                prefixEndTime);
        List<Wave> prefixWaves = new ArrayList<>(info.getEnemyWaves());
        prefixWaves.addAll(prefixFuture.allPredictedWaves);
        Map<Wave, List<BulletShadowUtil.ShadowInterval>> prefixShadowCache =
                waveShadowCacheBuilder.buildBaseShadowCache(prefixWaves);
        Map<Wave, PrecomputedWaveData> prefixPrecomputedWaveData =
                waveShadowCacheBuilder.buildPrecomputedWaveData(prefixWaves, prefixShadowCache);
        PhysicsUtil.Trajectory adjustedPrefix = new PhysicsUtil.Trajectory(prefixStates);
        PathDangerMetrics prefixMetrics = evaluatePathDangerMetrics(
                adjustedPrefix,
                prefixStartTime,
                prefixWaves,
                prefixShadowCache,
                prefixPrecomputedWaveData,
                0,
                prefixLength - 1,
                false);

        List<PathLeg> bestTailLegs = Collections.emptyList();
        double bestDanger = Double.POSITIVE_INFINITY;
        double bestOpponentDistance = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < candidateCount; i++) {
            List<PathLeg> tailLegs;

            if (i == 0 && hasCarryForward) {
                // First candidate: evaluate the carry-forward tail from the previous tick
                tailLegs = carryForwardTail;
            } else {
                // Generate random legs from prefix end state until all planning waves pass
                tailLegs = generateRandomTailLegs(prefixEndState, prefixEndTime,
                        planningWaves, minTailTicks, bfWidth, bfHeight);
            }

            // Simulate tail legs to get trajectory states
            List<PhysicsUtil.PositionState> tailStates = new ArrayList<>();
            tailStates.add(prefixEndState);
            PhysicsUtil.PositionState currentState = prefixEndState;
            long currentTime = prefixEndTime;
            for (PathLeg leg : tailLegs) {
                PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                        currentState,
                        leg.targetX,
                        leg.targetY,
                        currentTime,
                        null,
                        currentTime + leg.durationTicks,
                        PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                        leg.steeringMode,
                        bfWidth,
                        bfHeight);
                for (int s = 1; s < segment.states.length; s++) {
                    tailStates.add(segment.states[s]);
                }
                int segmentTicks = segment.length() - 1;
                currentState = segment.stateAt(segmentTicks);
                currentTime += segmentTicks;
            }

            // Extend carry-forward tail if it doesn't cover all planning waves
            if (i == 0 && hasCarryForward
                    && (!allPlanningWavesPassed(planningWaves, currentState, currentTime)
                        || (currentTime - prefixEndTime) < minTailTicks)) {
                tailLegs = new ArrayList<>(tailLegs);
                while (!allPlanningWavesPassed(planningWaves, currentState, currentTime)
                        || (currentTime - prefixEndTime) < minTailTicks) {
                    PathLeg leg = nextRandomTailLeg(currentState, bfWidth, bfHeight);
                    PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                            currentState,
                            leg.targetX,
                            leg.targetY,
                            currentTime,
                            null,
                            currentTime + leg.durationTicks,
                            PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                            leg.steeringMode,
                            bfWidth,
                            bfHeight);
                    for (int s = 1; s < segment.states.length; s++) {
                        tailStates.add(segment.states[s]);
                    }
                    int segmentTicks = segment.length() - 1;
                    tailLegs.add(leg);
                    currentState = segment.stateAt(segmentTicks);
                    currentTime += segmentTicks;
                }
            }

            // Build full trajectory: collision-adjusted prefix + tail
            PhysicsUtil.PositionState[] fullStates =
                    new PhysicsUtil.PositionState[prefixLength + tailStates.size() - 1];
            System.arraycopy(prefixStates, 0, fullStates, 0, prefixLength);
            for (int s = 1; s < tailStates.size(); s++) {
                fullStates[prefixLength + s - 1] = tailStates.get(s);
            }
            PhysicsUtil.Trajectory fullTrajectory = new PhysicsUtil.Trajectory(fullStates);

            // Continue opponent simulation (with collision handling) from the prefix end.
            FuturePredictionState fullFuture = continueFuturePredictionState(
                    fullStates,
                    prefixStartTime,
                    prefixLength - 1,
                    prefixFuture,
                    opponentInstruction);

            // Score danger against all waves (real + all virtual, no depth limit)
            List<Wave> allWaves = new ArrayList<>(info.getEnemyWaves());
            allWaves.addAll(fullFuture.allPredictedWaves);
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache =
                    waveShadowCacheBuilder.buildBaseShadowCache(allWaves);
            Map<Wave, PrecomputedWaveData> precomputedWaveData =
                    waveShadowCacheBuilder.buildPrecomputedWaveData(allWaves, shadowCache);
            PathDangerMetrics tailMetrics = evaluatePathDangerMetrics(
                    fullTrajectory,
                    prefixStartTime,
                    allWaves,
                    shadowCache,
                    precomputedWaveData,
                    prefixLength,
                    fullTrajectory.length() - 1,
                    true);
            double danger = prefixMetrics.expectedBulletDamageTaken + tailMetrics.expectedBulletDamageTaken;

            // Distance from opponent as tiebreaker when danger is equal
            double opponentDistance = 0.0;
            if (fullFuture.opponentState != null) {
                PhysicsUtil.PositionState tailEnd = fullStates[fullStates.length - 1];
                opponentDistance = Math.hypot(
                        tailEnd.x - fullFuture.opponentState.x,
                        tailEnd.y - fullFuture.opponentState.y);
            }

            if (danger < bestDanger - 1e-9
                    || (Math.abs(danger - bestDanger) <= 1e-9
                        && opponentDistance > bestOpponentDistance)) {
                bestDanger = danger;
                bestOpponentDistance = opponentDistance;
                bestTailLegs = tailLegs;
            }
        }

        return bestTailLegs;
    }

    private static PathLeg nextRandomTailLeg(PhysicsUtil.PositionState currentState,
                                              double bfWidth,
                                              double bfHeight) {
        double targetX, targetY;
        do {
            double angle = random.nextDouble() * (2.0 * Math.PI);
            double distance = Math.sqrt(random.nextDouble())
                    * BotConfig.Movement.MAX_TAIL_SEGMENT_TARGET_DISTANCE;
            targetX = currentState.x + FastTrig.sin(angle) * distance;
            targetY = currentState.y + FastTrig.cos(angle) * distance;
        } while (!PhysicsUtil.isWithinBattlefield(targetX, targetY, bfWidth, bfHeight));

        int durationTicks = 1 + random.nextInt(BotConfig.Movement.MAX_TAIL_SEGMENT_DURATION_TICKS);
        return new PathLeg(targetX, targetY, durationTicks, PhysicsUtil.SteeringMode.DIRECT);
    }

    private static List<PathLeg> generateRandomTailLegs(PhysicsUtil.PositionState startState,
                                                          long startTime,
                                                          List<Wave> planningWaves,
                                                          int minTailTicks,
                                                          double bfWidth,
                                                          double bfHeight) {
        List<PathLeg> legs = new ArrayList<>();
        PhysicsUtil.PositionState currentState = startState;
        long currentTime = startTime;
        while (!allPlanningWavesPassed(planningWaves, currentState, currentTime)
                || (currentTime - startTime) < minTailTicks
                || legs.isEmpty()) {
            PathLeg leg = nextRandomTailLeg(currentState, bfWidth, bfHeight);
            PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                    currentState,
                    leg.targetX,
                    leg.targetY,
                    currentTime,
                    null,
                    currentTime + leg.durationTicks,
                    PhysicsUtil.EndpointBehavior.PASS_THROUGH,
                    leg.steeringMode,
                    bfWidth,
                    bfHeight);
            int segmentTicks = segment.length() - 1;
            legs.add(leg);
            currentState = segment.stateAt(segmentTicks);
            currentTime += segmentTicks;
        }
        return legs;
    }

    private static boolean allPlanningWavesPassed(List<Wave> planningWaves,
                                                   PhysicsUtil.PositionState state,
                                                   long time) {
        for (Wave wave : planningWaves) {
            if (!wave.hasPassed(state.x, state.y, time)) {
                return false;
            }
        }
        return true;
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




