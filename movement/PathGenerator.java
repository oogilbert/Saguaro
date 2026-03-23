package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.PhysicsUtil;

final class PathGenerator {
    private static final long RANDOM_FAMILY_SALT = 0x1F123BB5E2D7AB4DL;
    private static final long EXACT_CARRY_FORWARD_SALT = 0x61C8864680B583EBL;
    private static final long REBUILD_PATH_SALT = 0x94D049BB133111EBL;
    private static final long MUTATION_PATH_SALT = 0xD2B74407B1CE6E93L;
    private static final long INDEX_SALT_STEP = 0x9E3779B97F4A7C15L;
    private static final long FIRST_WAVE_CW_FAMILY_SALT = 0xA24BAED4963EE407L;
    private static final long FIRST_WAVE_CENTER_FAMILY_SALT = 0x9FB21C651E98DF25L;
    private static final long FIRST_WAVE_CCW_FAMILY_SALT = 0xC6BC279692B5CC83L;
    private static final long SECOND_WAVE_CW_FAMILY_SALT = 0x8E4D22A6E175FA53L;
    private static final long SECOND_WAVE_CENTER_FAMILY_SALT = 0xB7041CE4186D3A91L;
    private static final long SECOND_WAVE_CCW_FAMILY_SALT = 0xD54F28B7C19FE0D3L;

    private enum AnchorMovement {
        CW(FIRST_WAVE_CW_FAMILY_SALT, SECOND_WAVE_CW_FAMILY_SALT, 1),
        CENTER(FIRST_WAVE_CENTER_FAMILY_SALT, SECOND_WAVE_CENTER_FAMILY_SALT, 0),
        CCW(FIRST_WAVE_CCW_FAMILY_SALT, SECOND_WAVE_CCW_FAMILY_SALT, -1);

        final long familySalt;
        final long secondWaveFamilySalt;
        final int tangentialDirectionSign;

        AnchorMovement(long familySalt, long secondWaveFamilySalt, int tangentialDirectionSign) {
            this.familySalt = familySalt;
            this.secondWaveFamilySalt = secondWaveFamilySalt;
            this.tangentialDirectionSign = tangentialDirectionSign;
        }
    }

    private static final AnchorMovement[] ANCHOR_MOVEMENTS = AnchorMovement.values();

    private static final class SegmentReplay {
        final PhysicsUtil.PositionState endState;
        final long endTime;
        final int segmentTicks;

        SegmentReplay(PhysicsUtil.PositionState endState, long endTime, int segmentTicks) {
            this.endState = endState;
            this.endTime = endTime;
            this.segmentTicks = segmentTicks;
        }
    }

    private static final class FamilyBudget {
        final int randomFamilyCount;
        final int maxReusedFamilyCount;
        final int primaryFamilyRebuildCount;
        final int primaryFamilyMutationCount;
        final int secondaryFamilyRebuildCount;
        final int secondaryFamilyMutationCount;

        FamilyBudget(int randomFamilyCount,
                     int maxReusedFamilyCount,
                     int primaryFamilyRebuildCount,
                     int primaryFamilyMutationCount,
                     int secondaryFamilyRebuildCount,
                     int secondaryFamilyMutationCount) {
            this.randomFamilyCount = randomFamilyCount;
            this.maxReusedFamilyCount = maxReusedFamilyCount;
            this.primaryFamilyRebuildCount = primaryFamilyRebuildCount;
            this.primaryFamilyMutationCount = primaryFamilyMutationCount;
            this.secondaryFamilyRebuildCount = secondaryFamilyRebuildCount;
            this.secondaryFamilyMutationCount = secondaryFamilyMutationCount;
        }

        int estimatedCapacity() {
            return randomFamilyCount
                    + Math.max(0, maxReusedFamilyCount)
                    + primaryFamilyRebuildCount
                    + primaryFamilyMutationCount
                    + Math.max(0, maxReusedFamilyCount - 1)
                    * (secondaryFamilyRebuildCount + secondaryFamilyMutationCount);
        }
    }

    private final MovementEngine movement;

    PathGenerator(MovementEngine movement) {
        if (movement == null) {
            throw new IllegalArgumentException("PathGenerator requires non-null movement");
        }
        this.movement = movement;
    }

    List<CandidatePath> generateCandidatePaths(
            PathGenerationContext context,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            List<CandidatePath> carriedForwardFamilies) {
        if (context == null) {
            throw new IllegalArgumentException("PathGenerator requires a non-null context");
        }
        if (context.planningWaves == null || context.planningWaves.isEmpty()) {
            throw new IllegalArgumentException("PathGenerator requires at least one planning wave");
        }
        return generateFamilyCandidatePaths(context, shadowCache, precomputedWaveData, carriedForwardFamilies);
    }

    private List<CandidatePath> generateFamilyCandidatePaths(
            PathGenerationContext context,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            List<CandidatePath> carriedForwardFamilies) {
        int availableReusableFamilyCount = carriedForwardFamilies == null
                ? 0
                : Math.min(carriedForwardFamilies.size(), BotConfig.Movement.MAX_REUSED_FAMILY_COUNT);
        int planningHorizonTicks = estimatePlanningHorizonTicks(context);
        FamilyBudget familyBudget = familyBudgetForContext(context, planningHorizonTicks, availableReusableFamilyCount);
        Wave firstPlanningWave = context.planningWaves.get(0);
        int requestedAnchorFamilyCount = anchorFamilyCountForContext(context);
        int freshFamilyCount = Math.max(
                familyBudget.randomFamilyCount,
                requestedAnchorFamilyCount + minimumRandomFamilyCountForContext(context));
        List<CandidatePath> results = new ArrayList<>(
                familyBudget.estimatedCapacity() + Math.max(0, freshFamilyCount - familyBudget.randomFamilyCount));

        if (carriedForwardFamilies != null) {
            int familyCount = Math.min(carriedForwardFamilies.size(), familyBudget.maxReusedFamilyCount);
            for (int familyIndex = 0; familyIndex < familyCount; familyIndex++) {
                CandidatePath previousFamilyPath = carriedForwardFamilies.get(familyIndex);
                if (previousFamilyPath == null) {
                    continue;
                }
                boolean primaryFamily = familyIndex == 0;
                addReusableFamilyCandidates(
                        results,
                        context,
                        firstPlanningWave,
                        shadowCache,
                        precomputedWaveData,
                        previousFamilyPath,
                        primaryFamily
                                ? familyBudget.primaryFamilyRebuildCount
                                : familyBudget.secondaryFamilyRebuildCount,
                        primaryFamily
                                ? familyBudget.primaryFamilyMutationCount
                                : familyBudget.secondaryFamilyMutationCount);
            }
        }

        int anchoredFamilyCount = Math.min(requestedAnchorFamilyCount, freshFamilyCount);
        if (context.planningWaves.size() >= 2) {
            Wave secondPlanningWave = context.planningWaves.get(1);
            int familyIndex = 0;
            for (AnchorMovement firstWaveMovement : ANCHOR_MOVEMENTS) {
                for (AnchorMovement secondWaveMovement : ANCHOR_MOVEMENTS) {
                    if (familyIndex >= anchoredFamilyCount) {
                        break;
                    }
                    long familyId = computeContextSeed(
                            context,
                            anchorFamilySalt(firstWaveMovement, secondWaveMovement));
                    Random familyRandom = new Random(familyId);
                    results.add(buildAnchoredWavePath(
                            context,
                            firstPlanningWave,
                            secondPlanningWave,
                            shadowCache,
                            precomputedWaveData,
                            firstWaveMovement,
                            secondWaveMovement,
                            familyId,
                            familyRandom));
                    familyIndex++;
                }
            }
        } else {
            for (int familyIndex = 0; familyIndex < anchoredFamilyCount; familyIndex++) {
                AnchorMovement firstWaveMovement = ANCHOR_MOVEMENTS[familyIndex];
                long familyId = computeContextSeed(context, firstWaveMovement.familySalt);
                Random familyRandom = new Random(familyId);
                results.add(buildAnchoredWavePath(
                        context,
                        firstPlanningWave,
                        null,
                        shadowCache,
                        precomputedWaveData,
                        firstWaveMovement,
                        null,
                        familyId,
                        familyRandom));
            }
        }

        for (int familyIndex = anchoredFamilyCount; familyIndex < freshFamilyCount; familyIndex++) {
            long familyId = computeContextSeed(context, RANDOM_FAMILY_SALT ^ indexedSalt(familyIndex));
            Random familyRandom = new Random(familyId);
            CandidatePath candidate = buildRandomSegmentPath(
                    context,
                    firstPlanningWave,
                    shadowCache,
                    precomputedWaveData,
                    familyId,
                    familyRandom);
            results.add(candidate);
        }
        movement.recordPathPlanningDiagnostics(new MovementEngine.PathPlanningDiagnostics(
                context.currentTime,
                context.planningWaves.size(),
                planningHorizonTicks,
                results.size(),
                anchoredFamilyCount,
                freshFamilyCount - anchoredFamilyCount,
                familyBudget.maxReusedFamilyCount,
                familyBudget.primaryFamilyRebuildCount,
                familyBudget.primaryFamilyMutationCount,
                familyBudget.secondaryFamilyRebuildCount,
                familyBudget.secondaryFamilyMutationCount));
        return results;
    }

    private static FamilyBudget familyBudgetForContext(PathGenerationContext context,
                                                       int planningHorizonTicks,
                                                       int availableReusableFamilyCount) {
        double scale = Math.pow(
                BotConfig.Movement.REFERENCE_PLANNING_HORIZON_TICKS / planningHorizonTicks,
                BotConfig.Movement.FAMILY_BUDGET_SCALE_EXPONENT);
        scale = Math.max(
                BotConfig.Movement.MIN_FAMILY_BUDGET_SCALE,
                Math.min(BotConfig.Movement.MAX_FAMILY_BUDGET_SCALE, scale));
        double earlyTurnBudgetMultiplier = earlyTurnBudgetMultiplier(context.currentTime);
        double planningWaveBudgetMultiplier = planningWaveBudgetMultiplier(context.planningWaves.size());
        int clampedReusableFamilyCount = Math.min(
                Math.max(0, availableReusableFamilyCount),
                BotConfig.Movement.MAX_REUSED_FAMILY_COUNT);
        int additionalBudgetReference = BotConfig.Movement.REFERENCE_EXTRA_RANDOM_FAMILY_COUNT;
        if (clampedReusableFamilyCount >= 1) {
            additionalBudgetReference += BotConfig.Movement.REFERENCE_PRIMARY_FAMILY_OPTIONAL_SLOTS;
        }
        int baseRandomFamilyCount = Math.max(
                1,
                (int) Math.round(BotConfig.Movement.BASE_RANDOM_FAMILY_COUNT * earlyTurnBudgetMultiplier));
        int additionalBudget = (int) Math.round(
                additionalBudgetReference
                        * scale
                        * BotConfig.Movement.ADDITIONAL_BUDGET_MULTIPLIER
                        * earlyTurnBudgetMultiplier
                        * planningWaveBudgetMultiplier);

        int primaryOptionalSlots = 0;
        int extraRandomSlots = 0;
        if (clampedReusableFamilyCount == 0) {
            extraRandomSlots = additionalBudget;
        } else {
            int[] categoryWeights = new int[]{
                    BotConfig.Movement.REFERENCE_PRIMARY_FAMILY_OPTIONAL_SLOTS,
                    BotConfig.Movement.REFERENCE_EXTRA_RANDOM_FAMILY_COUNT
            };
            int[] categoryAllocations = apportionSlots(categoryWeights, additionalBudget, 2);
            primaryOptionalSlots = categoryAllocations[0];
            extraRandomSlots = categoryAllocations[1];
        }

        return new FamilyBudget(
                baseRandomFamilyCount + extraRandomSlots,
                clampedReusableFamilyCount >= 1 ? 1 : 0,
                splitLeadingSlotCount(primaryOptionalSlots),
                splitTrailingSlotCount(primaryOptionalSlots),
                0,
                0);
    }

    private static int anchorFamilyCountForContext(PathGenerationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Anchor family count requires a non-null context");
        }
        return context.planningWaves.size() >= 2
                ? BotConfig.Movement.DUAL_WAVE_ANCHOR_FAMILY_COUNT
                : BotConfig.Movement.SINGLE_WAVE_ANCHOR_FAMILY_COUNT;
    }

    private static int minimumRandomFamilyCountForContext(PathGenerationContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Minimum random family count requires a non-null context");
        }
        return context.planningWaves.size() >= 2 ? 1 : 0;
    }

    private static long anchorFamilySalt(AnchorMovement firstWaveMovement,
                                         AnchorMovement secondWaveMovement) {
        if (firstWaveMovement == null) {
            throw new IllegalArgumentException("Anchor family salt requires a first-wave movement");
        }
        long salt = firstWaveMovement.familySalt;
        if (secondWaveMovement != null) {
            salt ^= Long.rotateLeft(secondWaveMovement.secondWaveFamilySalt, 1);
        }
        return salt;
    }

    private static int estimatePlanningHorizonTicks(PathGenerationContext context) {
        double robotX = context.startState.x;
        double robotY = context.startState.y;
        long currentTime = context.currentTime;
        int horizonTicks = Math.max(1, context.minPathTicks);
        for (Wave wave : context.planningWaves) {
            horizonTicks = Math.max(horizonTicks, PhysicsUtil.waveArrivalTick(wave, robotX, robotY, currentTime));
        }
        return horizonTicks;
    }

    private static double earlyTurnBudgetMultiplier(long currentTime) {
        if (currentTime <= BotConfig.Movement.EARLY_TURN_MIN_BUDGET_HOLD_TICK) {
            return BotConfig.Movement.MIN_EARLY_TURN_BUDGET_MULTIPLIER;
        }
        double progress = Math.max(
                0.0,
                Math.min(
                        1.0,
                        (currentTime - BotConfig.Movement.EARLY_TURN_MIN_BUDGET_HOLD_TICK)
                                / (double) (BotConfig.Movement.FULL_EARLY_TURN_BUDGET_TICK
                                - BotConfig.Movement.EARLY_TURN_MIN_BUDGET_HOLD_TICK)));
        double easedProgress = progress;
        return BotConfig.Movement.MIN_EARLY_TURN_BUDGET_MULTIPLIER
                + (1.0 - BotConfig.Movement.MIN_EARLY_TURN_BUDGET_MULTIPLIER) * easedProgress;
    }

    private static double planningWaveBudgetMultiplier(int planningWaveCount) {
        if (planningWaveCount <= 1) {
            return 1.0;
        }
        return Math.pow(BotConfig.Movement.EXTRA_PLANNING_WAVE_BUDGET_MULTIPLIER, planningWaveCount - 1);
    }

    private static int[] apportionSlots(int[] weights, int slotCount, int categoryCount) {
        int[] allocations = new int[weights.length];
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            int bestIndex = -1;
            double bestPriority = Double.NEGATIVE_INFINITY;
            for (int categoryIndex = 0; categoryIndex < categoryCount; categoryIndex++) {
                int weight = weights[categoryIndex];
                if (weight <= 0) {
                    continue;
                }
                double priority = (double) weight / (allocations[categoryIndex] + 1);
                if (priority > bestPriority) {
                    bestPriority = priority;
                    bestIndex = categoryIndex;
                }
            }
            allocations[bestIndex]++;
        }
        return allocations;
    }

    private static int splitLeadingSlotCount(int slotCount) {
        return slotCount <= 0 ? 0 : (slotCount + 1) / 2;
    }

    private static int splitTrailingSlotCount(int slotCount) {
        return slotCount <= 0 ? 0 : slotCount / 2;
    }

    private void addReusableFamilyCandidates(
            List<CandidatePath> results,
            PathGenerationContext context,
            Wave firstPlanningWave,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            CandidatePath previousFamilyPath,
            int rebuildCount,
            int mutationCount) {
        ReusablePathState reusablePathState = prepareReusablePathState(context, previousFamilyPath);
        results.add(buildCarriedForwardPath(
                context,
                firstPlanningWave,
                shadowCache,
                precomputedWaveData,
                previousFamilyPath,
                reusablePathState));
        for (int rebuildIndex = 0; rebuildIndex < rebuildCount; rebuildIndex++) {
            results.add(buildRebuiltCarryForwardPath(
                    context,
                    firstPlanningWave,
                    shadowCache,
                    precomputedWaveData,
                    previousFamilyPath,
                    reusablePathState,
                    rebuildIndex));
        }
        for (int mutationIndex = 0; mutationIndex < mutationCount; mutationIndex++) {
            results.add(buildMutatedCarryForwardPath(
                    context,
                    firstPlanningWave,
                    shadowCache,
                    precomputedWaveData,
                    previousFamilyPath,
                    reusablePathState,
                    mutationIndex));
        }
    }

    private CandidatePath buildRandomSegmentPath(
            PathGenerationContext context,
            Wave firstPlanningWave,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            long familyId,
            Random pathRandom) {
        PathBuildResult buildResult = buildPathFromLegs(
                context,
                pathRandom,
                Collections.<PathLeg>emptyList());
        return movement.buildCandidatePath(
                buildResult.trajectory,
                context.currentTime,
                context.scoringWaves,
                shadowCache,
                buildResult.firstTargetX,
                buildResult.firstTargetY,
                buildResult.firstTargetAngle,
                Double.NaN,
                Double.NaN,
                firstPlanningWave,
                WaveStrategy.STRAIGHT,
                -1,
                buildResult.firstLegDurationTicks,
                precomputedWaveData,
                buildResult.segmentLegs,
                familyId);
    }

    private CandidatePath buildAnchoredWavePath(
            PathGenerationContext context,
            Wave firstPlanningWave,
            Wave secondPlanningWave,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            AnchorMovement firstWaveMovement,
            AnchorMovement secondWaveMovement,
            long familyId,
            Random pathRandom) {
        PathBuildResult buildResult = buildPathFromLegs(
                context,
                pathRandom,
                buildAnchoredWaveLegs(
                        context,
                        firstPlanningWave,
                        secondPlanningWave,
                        firstWaveMovement,
                        secondWaveMovement));
        return movement.buildCandidatePath(
                buildResult.trajectory,
                context.currentTime,
                context.scoringWaves,
                shadowCache,
                buildResult.firstTargetX,
                buildResult.firstTargetY,
                buildResult.firstTargetAngle,
                Double.NaN,
                Double.NaN,
                firstPlanningWave,
                WaveStrategy.STRAIGHT,
                -1,
                buildResult.firstLegDurationTicks,
                precomputedWaveData,
                buildResult.segmentLegs,
                familyId);
    }

    private CandidatePath buildCarriedForwardPath(
            PathGenerationContext context,
            Wave firstPlanningWave,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            CandidatePath previousPath,
            ReusablePathState reusablePathState) {
        List<PathLeg> remainingLegs = trimConsumedLegs(previousPath.segmentLegs, reusablePathState.elapsedTicks);
        Random extensionRandom = createVariantRandom(
                context,
                EXACT_CARRY_FORWARD_SALT,
                previousPath.familyId,
                reusablePathState.elapsedTicks,
                0);
        PathBuildResult buildResult = buildPathFromLegs(context, extensionRandom, remainingLegs);
        return movement.buildCandidatePath(
                buildResult.trajectory,
                context.currentTime,
                context.scoringWaves,
                shadowCache,
                buildResult.firstTargetX,
                buildResult.firstTargetY,
                buildResult.firstTargetAngle,
                Double.NaN,
                Double.NaN,
                firstPlanningWave,
                WaveStrategy.STRAIGHT,
                -1,
                buildResult.firstLegDurationTicks,
                precomputedWaveData,
                buildResult.segmentLegs,
                previousPath.familyId);
    }

    private CandidatePath buildRebuiltCarryForwardPath(
            PathGenerationContext context,
            Wave firstPlanningWave,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            CandidatePath previousPath,
            ReusablePathState reusablePathState,
            int rebuildIndex) {
        int preservedLegCount = preservedLegCountThroughWaveContact(previousPath, firstPlanningWave);
        List<PathLeg> preservedSourceLegs = preservedLegCount > 0
                ? new ArrayList<>(previousPath.segmentLegs.subList(0, preservedLegCount))
                : Collections.<PathLeg>emptyList();
        List<PathLeg> remainingLegs = trimConsumedLegs(preservedSourceLegs, reusablePathState.elapsedTicks);
        Random extensionRandom = createVariantRandom(
                context,
                REBUILD_PATH_SALT,
                previousPath.familyId,
                reusablePathState.elapsedTicks,
                rebuildIndex);
        PathBuildResult buildResult = buildPathFromLegs(context, extensionRandom, remainingLegs);
        return movement.buildCandidatePath(
                buildResult.trajectory,
                context.currentTime,
                context.scoringWaves,
                shadowCache,
                buildResult.firstTargetX,
                buildResult.firstTargetY,
                buildResult.firstTargetAngle,
                Double.NaN,
                Double.NaN,
                firstPlanningWave,
                WaveStrategy.STRAIGHT,
                -1,
                buildResult.firstLegDurationTicks,
                precomputedWaveData,
                buildResult.segmentLegs,
                previousPath.familyId);
    }

    private CandidatePath buildMutatedCarryForwardPath(
            PathGenerationContext context,
            Wave firstPlanningWave,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache,
            Map<Wave, MovementEngine.PrecomputedWaveData> precomputedWaveData,
            CandidatePath previousPath,
            ReusablePathState reusablePathState,
            int mutationIndex) {
        List<PathLeg> remainingLegs = trimConsumedLegs(previousPath.segmentLegs, reusablePathState.elapsedTicks);
        Random mutationRandom = createVariantRandom(
                context,
                MUTATION_PATH_SALT,
                previousPath.familyId,
                reusablePathState.elapsedTicks,
                mutationIndex);
        List<PathLeg> mutatedLegs = mutateRemainingLegs(remainingLegs, context.bfWidth, context.bfHeight, mutationRandom);
        PathBuildResult buildResult = buildPathFromLegs(context, mutationRandom, mutatedLegs);
        return movement.buildCandidatePath(
                buildResult.trajectory,
                context.currentTime,
                context.scoringWaves,
                shadowCache,
                buildResult.firstTargetX,
                buildResult.firstTargetY,
                buildResult.firstTargetAngle,
                Double.NaN,
                Double.NaN,
                firstPlanningWave,
                WaveStrategy.STRAIGHT,
                -1,
                buildResult.firstLegDurationTicks,
                precomputedWaveData,
                buildResult.segmentLegs,
                previousPath.familyId);
    }

    private static ReusablePathState prepareReusablePathState(PathGenerationContext context,
                                                              CandidatePath previousPath) {
        long elapsedTicksLong = context.currentTime - previousPath.startTime;
        if (elapsedTicksLong < 0L) {
            elapsedTicksLong = 0L;
        }
        if (elapsedTicksLong > Integer.MAX_VALUE) {
            elapsedTicksLong = Integer.MAX_VALUE;
        }
        return new ReusablePathState((int) elapsedTicksLong);
    }

    private static List<PathLeg> buildAnchoredWaveLegs(PathGenerationContext context,
                                                       Wave firstPlanningWave,
                                                       Wave secondPlanningWave,
                                                       AnchorMovement firstWaveMovement,
                                                       AnchorMovement secondWaveMovement) {
        if (context == null || firstPlanningWave == null || firstWaveMovement == null) {
            throw new IllegalArgumentException(
                    "Anchor wave generation requires non-null context, first planning wave, and first movement");
        }
        List<PathLeg> anchorLegs = new ArrayList<>(secondPlanningWave != null && secondWaveMovement != null ? 2 : 1);
        PhysicsUtil.PositionState currentState = context.startState;
        long currentTime = context.currentTime;

        PathLeg firstLeg = buildAnchorLeg(context, firstPlanningWave, currentState, currentTime, firstWaveMovement);
        anchorLegs.add(firstLeg);

        if (secondPlanningWave == null || secondWaveMovement == null) {
            return anchorLegs;
        }

        List<PhysicsUtil.PositionState> replayStates = new ArrayList<>(2);
        replayStates.add(currentState);
        SegmentReplay firstReplay = appendLegToPath(
                firstLeg,
                currentState,
                currentTime,
                context.bfWidth,
                context.bfHeight,
                replayStates);
        currentState = firstReplay.endState;
        currentTime = firstReplay.endTime;
        if (!secondPlanningWave.hasPassed(currentState.x, currentState.y, currentTime)) {
            anchorLegs.add(buildAnchorLeg(
                    context,
                    secondPlanningWave,
                    currentState,
                    currentTime,
                    secondWaveMovement));
        }
        return anchorLegs;
    }

    private static PathLeg buildAnchorLeg(PathGenerationContext context,
                                          Wave planningWave,
                                          PhysicsUtil.PositionState state,
                                          long currentTime,
                                          AnchorMovement anchorMovement) {
        if (context == null || planningWave == null || state == null || anchorMovement == null) {
            throw new IllegalArgumentException("Anchor leg generation requires non-null inputs");
        }
        int durationTicks = Math.max(1, ticksUntilWavePasses(planningWave, state, currentTime));
        if (anchorMovement == AnchorMovement.CENTER) {
            return new PathLeg(state.x, state.y, durationTicks);
        }

        double currentBearing = Math.atan2(state.x - planningWave.originX, state.y - planningWave.originY);
        double travelAngle = currentBearing + anchorMovement.tangentialDirectionSign * (Math.PI * 0.5);
        double maxDistance = MovementEngine.maxDistanceInField(
                state.x,
                state.y,
                travelAngle,
                context.bfWidth,
                context.bfHeight);
        double targetX = MovementEngine.clampToField(
                state.x + FastTrig.sin(travelAngle) * maxDistance,
                context.bfWidth,
                true);
        double targetY = MovementEngine.clampToField(
                state.y + FastTrig.cos(travelAngle) * maxDistance,
                context.bfHeight,
                false);
        return new PathLeg(targetX, targetY, durationTicks);
    }

    private PathBuildResult buildPathFromLegs(
            PathGenerationContext context,
            Random pathRandom,
            List<PathLeg> initialLegs) {
        List<PhysicsUtil.PositionState> pathStates = new ArrayList<>();
        pathStates.add(context.startState);

        PhysicsUtil.PositionState currentState = context.startState;
        long currentTime = context.currentTime;
        List<PathLeg> segmentLegs = new ArrayList<>(initialLegs.size() + 4);
        double firstTargetX = Double.NaN;
        double firstTargetY = Double.NaN;
        double firstTargetAngle = Double.NaN;
        int firstLegDurationTicks = -1;

        for (PathLeg leg : initialLegs) {
            SegmentReplay replay = appendLegToPath(
                    leg,
                    currentState,
                    currentTime,
                    context.bfWidth,
                    context.bfHeight,
                    pathStates);
            segmentLegs.add(leg);
            if (firstLegDurationTicks < 0) {
                firstTargetX = leg.targetX;
                firstTargetY = leg.targetY;
                firstTargetAngle = Math.atan2(leg.targetX - currentState.x, leg.targetY - currentState.y);
                firstLegDurationTicks = replay.segmentTicks;
            }
            currentState = replay.endState;
            currentTime = replay.endTime;
        }

        while (!allPlanningWavesPassed(context.planningWaves, currentState, currentTime)
                || (currentTime - context.currentTime) < context.minPathTicks) {
            PathLeg leg = nextSegmentLeg(currentState, context.bfWidth, context.bfHeight, pathRandom);
            SegmentReplay replay = appendLegToPath(
                    leg,
                    currentState,
                    currentTime,
                    context.bfWidth,
                    context.bfHeight,
                    pathStates);
            segmentLegs.add(leg);
            if (firstLegDurationTicks < 0) {
                firstTargetX = leg.targetX;
                firstTargetY = leg.targetY;
                firstTargetAngle = Math.atan2(leg.targetX - currentState.x, leg.targetY - currentState.y);
                firstLegDurationTicks = replay.segmentTicks;
            }
            currentState = replay.endState;
            currentTime = replay.endTime;
        }

        PhysicsUtil.Trajectory trajectory = new PhysicsUtil.Trajectory(
                pathStates.toArray(new PhysicsUtil.PositionState[0]));
        return new PathBuildResult(
                trajectory,
                Collections.unmodifiableList(segmentLegs),
                firstTargetX,
                firstTargetY,
                firstTargetAngle,
                firstLegDurationTicks);
    }

    private static SegmentReplay appendLegToPath(PathLeg leg,
                                                 PhysicsUtil.PositionState currentState,
                                                 long currentTime,
                                                 double bfWidth,
                                                 double bfHeight,
                                                 List<PhysicsUtil.PositionState> pathStates) {
        PhysicsUtil.Trajectory segment = PhysicsUtil.simulateTrajectory(
                currentState,
                leg.targetX,
                leg.targetY,
                currentTime,
                null,
                currentTime + leg.durationTicks,
                PhysicsUtil.EndpointBehavior.PARK_AND_WAIT,
                bfWidth,
                bfHeight);
        appendTrajectoryStates(pathStates, segment);
        int segmentTicks = segment.length() - 1;
        return new SegmentReplay(segment.stateAt(segmentTicks), currentTime + segmentTicks, segmentTicks);
    }

    private static List<PathLeg> trimConsumedLegs(List<PathLeg> sourceLegs, int elapsedTicks) {
        if (sourceLegs == null || sourceLegs.isEmpty()) {
            return Collections.emptyList();
        }
        int remainingTicks = Math.max(0, elapsedTicks);
        boolean keepingRemainder = false;
        List<PathLeg> remainingLegs = new ArrayList<>(sourceLegs.size());
        for (PathLeg leg : sourceLegs) {
            if (keepingRemainder) {
                remainingLegs.add(leg);
                continue;
            }
            if (remainingTicks >= leg.durationTicks) {
                remainingTicks -= leg.durationTicks;
                continue;
            }
            remainingLegs.add(new PathLeg(leg.targetX, leg.targetY, leg.durationTicks - remainingTicks));
            remainingTicks = 0;
            keepingRemainder = true;
        }
        return remainingLegs;
    }

    private static List<PathLeg> mutateRemainingLegs(List<PathLeg> sourceLegs,
                                                     double bfWidth,
                                                     double bfHeight,
                                                     Random mutationRandom) {
        if (sourceLegs == null || sourceLegs.isEmpty()) {
            return Collections.emptyList();
        }
        int mutateIndex = mutationRandom.nextInt(sourceLegs.size());
        List<PathLeg> mutatedLegs = new ArrayList<>(sourceLegs.size());
        for (int i = 0; i < sourceLegs.size(); i++) {
            PathLeg leg = sourceLegs.get(i);
            if (i == mutateIndex) {
                mutatedLegs.add(mutateLeg(leg, bfWidth, bfHeight, mutationRandom));
            } else {
                mutatedLegs.add(leg);
            }
        }
        return mutatedLegs;
    }

    private static PathLeg mutateLeg(PathLeg leg,
                                     double bfWidth,
                                     double bfHeight,
                                     Random mutationRandom) {
        if (mutationRandom.nextBoolean()) {
            return mutateLegTarget(leg, bfWidth, bfHeight, mutationRandom);
        }
        return mutateLegDuration(leg, mutationRandom);
    }

    private static PathLeg mutateLegTarget(PathLeg leg,
                                           double bfWidth,
                                           double bfHeight,
                                           Random mutationRandom) {
        double angle = mutationRandom.nextDouble() * (2.0 * Math.PI);
        double distance = Math.sqrt(mutationRandom.nextDouble())
                * BotConfig.Movement.MAX_MUTATION_TARGET_WIGGLE;
        double targetX = clampToReachableEdge(leg.targetX + FastTrig.sin(angle) * distance, bfWidth);
        double targetY = clampToReachableEdge(leg.targetY + FastTrig.cos(angle) * distance, bfHeight);
        return new PathLeg(targetX, targetY, leg.durationTicks);
    }

    private static PathLeg mutateLegDuration(PathLeg leg, Random mutationRandom) {
        int mutatedDuration = leg.durationTicks;
        for (int i = 0; i < 8 && mutatedDuration == leg.durationTicks; i++) {
            int durationDelta = randomNonZeroDurationDelta(mutationRandom);
            mutatedDuration = Math.max(1, leg.durationTicks + durationDelta);
        }
        if (mutatedDuration == leg.durationTicks) {
            mutatedDuration = leg.durationTicks + 1;
        }
        return new PathLeg(leg.targetX, leg.targetY, mutatedDuration);
    }

    private static int randomNonZeroDurationDelta(Random mutationRandom) {
        int durationDelta = 0;
        while (durationDelta == 0) {
            durationDelta = mutationRandom.nextInt(BotConfig.Movement.MAX_MUTATION_DURATION_DELTA * 2 + 1)
                    - BotConfig.Movement.MAX_MUTATION_DURATION_DELTA;
        }
        return durationDelta;
    }

    private static int preservedLegCountThroughWaveContact(CandidatePath previousPath, Wave firstPlanningWave) {
        if (previousPath.pathIntersections == null || previousPath.pathIntersections.isEmpty() || firstPlanningWave == null) {
            return 0;
        }
        long firstContactTime = Long.MIN_VALUE;
        for (PathWaveIntersection intersection : previousPath.pathIntersections) {
            if (intersection != null && wavesMatch(intersection.wave, firstPlanningWave)) {
                firstContactTime = intersection.firstContactTime;
                break;
            }
        }
        if (firstContactTime == Long.MIN_VALUE) {
            return 0;
        }
        long contactTickLong = firstContactTime - previousPath.startTime;
        if (contactTickLong < 0L) {
            contactTickLong = 0L;
        }
        int contactTick = contactTickLong > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) contactTickLong;
        int cumulativeTicks = 0;
        for (int i = 0; i < previousPath.segmentLegs.size(); i++) {
            cumulativeTicks += previousPath.segmentLegs.get(i).durationTicks;
            if (contactTick <= cumulativeTicks) {
                return i + 1;
            }
        }
        return previousPath.segmentLegs.size();
    }

    private static boolean wavesMatch(Wave a, Wave b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.fireTime == b.fireTime
                && a.isVirtual == b.isVirtual
                && Double.doubleToLongBits(a.originX) == Double.doubleToLongBits(b.originX)
                && Double.doubleToLongBits(a.originY) == Double.doubleToLongBits(b.originY)
                && Double.doubleToLongBits(a.speed) == Double.doubleToLongBits(b.speed);
    }

    private static PathLeg nextSegmentLeg(PhysicsUtil.PositionState currentState,
                                          double bfWidth,
                                          double bfHeight,
                                          Random pathRandom) {
        double targetX, targetY;
        do {
            double angle = pathRandom.nextDouble() * (2.0 * Math.PI);
            double distance = Math.sqrt(pathRandom.nextDouble())
                    * BotConfig.Movement.MAX_SEGMENT_TARGET_DISTANCE;
            targetX = currentState.x + FastTrig.sin(angle) * distance;
            targetY = currentState.y + FastTrig.cos(angle) * distance;
        } while (!PhysicsUtil.isWithinBattlefield(targetX, targetY, bfWidth, bfHeight));

        int durationTicks = 1 + pathRandom.nextInt(BotConfig.Movement.MAX_SEGMENT_DURATION_TICKS);
        return new PathLeg(targetX, targetY, durationTicks);
    }

    private static double clampToReachableEdge(double value, double fieldSize) {
        return Math.max(PhysicsUtil.WALL_MARGIN, Math.min(fieldSize - PhysicsUtil.WALL_MARGIN, value));
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

    private static int ticksUntilWavePasses(Wave wave,
                                            PhysicsUtil.PositionState state,
                                            long currentTime) {
        double remainingDistance = wave.maxDistanceToBody(state.x, state.y) - wave.getRadius(currentTime);
        if (remainingDistance <= 0.0) {
            return 0;
        }
        return (int) Math.ceil(remainingDistance / wave.speed);
    }

    private static void appendTrajectoryStates(List<PhysicsUtil.PositionState> pathStates,
                                               PhysicsUtil.Trajectory trajectory) {
        for (int i = 1; i < trajectory.states.length; i++) {
            pathStates.add(trajectory.states[i]);
        }
    }

    private static Random createVariantRandom(PathGenerationContext context,
                                              long salt,
                                              long familyId,
                                              int elapsedTicks,
                                              int variantIndex) {
        long variantSalt = salt
                ^ familyId
                ^ elapsedTicks
                ^ (((long) variantIndex + 1L) * INDEX_SALT_STEP);
        return new Random(computeContextSeed(context, variantSalt));
    }

    private static long computeContextSeed(PathGenerationContext context, long salt) {
        long seed = salt;
        seed = 31L * seed + context.currentTime;
        seed = 31L * seed + Double.doubleToLongBits(context.startState.x);
        seed = 31L * seed + Double.doubleToLongBits(context.startState.y);
        seed = 31L * seed + Double.doubleToLongBits(context.startState.heading);
        seed = 31L * seed + Double.doubleToLongBits(context.startState.velocity);
        seed = 31L * seed + Double.doubleToLongBits(context.opponentReferenceX);
        seed = 31L * seed + Double.doubleToLongBits(context.opponentReferenceY);
        for (Wave wave : context.planningWaves) {
            seed = 31L * seed + wave.fireTime;
            seed = 31L * seed + Double.doubleToLongBits(wave.originX);
            seed = 31L * seed + Double.doubleToLongBits(wave.originY);
            seed = 31L * seed + Double.doubleToLongBits(wave.speed);
        }
        return seed;
    }

    private static long indexedSalt(int index) {
        return ((long) index + 1L) * INDEX_SALT_STEP;
    }

    private static final class ReusablePathState {
        final int elapsedTicks;

        ReusablePathState(int elapsedTicks) {
            this.elapsedTicks = elapsedTicks;
        }
    }
}


