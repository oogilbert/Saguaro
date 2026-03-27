package oog.mega.saguaro.mode.shotdodger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import oog.mega.saguaro.movement.RamEvent;
import robocode.Rules;

final class ShotDodgerPowerScorer {
    private static final double MIN_FIRE_POWER = 0.1;
    private static final double MIN_TERMINAL_RATE = 1e-6;
    private static final double EVENT_TIME_EPSILON = 1e-9;

    private Info info;
    private final RobocodeScoreUtil.HitScoreScratch hitScoreScratch = new RobocodeScoreUtil.HitScoreScratch();

    void init(Info info) {
        this.info = info;
    }

    PowerSelection selectBestPower(CandidatePath path,
                                   List<PathWaveIntersection> pathIntersections,
                                   RobotSnapshot robotState,
                                   int firstFiringTickOffset) {
        if (path == null || robotState == null) {
            return new PowerSelection(0.0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        }
        PathEvaluationTemplate template = buildPathEvaluationTemplate(path, pathIntersections);
        double enemyEnergy = currentEnemyEnergyForScoring();
        double maxPower = maxCandidateOffensivePower(robotState.energy, enemyEnergy);
        PowerSelection bestSelection = evaluatePower(
                path,
                template,
                robotState,
                firstFiringTickOffset,
                0.0);
        for (double candidatePower : buildCandidateOffensivePowers(maxPower)) {
            PowerSelection selection = evaluatePower(
                    path,
                    template,
                    robotState,
                    firstFiringTickOffset,
                    candidatePower);
            if (isBetterSelection(selection, bestSelection)) {
                bestSelection = selection;
            }
        }
        return bestSelection;
    }

    private PowerSelection evaluatePower(CandidatePath path,
                                         PathEvaluationTemplate template,
                                         RobotSnapshot robotState,
                                         int firstFiringTickOffset,
                                         double firePower) {
        BranchedEventSchedule schedule = buildBranchedEventSchedule(
                path,
                template,
                robotState,
                firstFiringTickOffset,
                firePower);
        List<ImmediateEvent> events = schedule.events;

        double currentEnemyEnergy = currentEnemyEnergyForScoring();
        BranchState initialBranch = new BranchState(
                1.0,
                info.getOurScore(),
                info.getOpponentScore(),
                Math.max(0.0, robotState.energy),
                currentEnemyEnergy,
                Math.max(0.0, info.getOurCreditedDamageOnEnemyThisRound()),
                Math.max(0.0, info.getEnemyCreditedDamageOnUsThisRound()),
                false,
                false,
                robotState.energy > 0.0,
                currentEnemyEnergy > 0.0,
                false);

        List<BranchState> branches = new ArrayList<>();
        List<BranchState> nextBranches = new ArrayList<>();
        List<BranchState> activeBranches = new ArrayList<>();
        branches.add(initialBranch);
        Aggregation aggregation = new Aggregation();
        double currentEventTime = Double.NaN;

        for (ImmediateEvent event : events) {
            if (Double.isFinite(currentEventTime) && !sameEventTime(event.time, currentEventTime)) {
                activeBranches.clear();
                settleResolvedBranches(branches, activeBranches, aggregation);
                branches.clear();
                branches.addAll(activeBranches);
                if (branches.isEmpty()) {
                    return new PowerSelection(
                            firePower,
                            aggregation.weightedValue,
                            aggregation.weightedAbsoluteScore);
                }
            }
            currentEventTime = event.time;
            nextBranches.clear();
            for (BranchState branch : branches) {
                applyImmediateEvent(nextBranches, branch, event);
            }
            branches.clear();
            for (BranchState branch : nextBranches) {
                if (branch != null && branch.probability > 0.0) {
                    branches.add(branch);
                }
            }
            if (branches.isEmpty()) {
                return new PowerSelection(
                        firePower,
                        aggregation.weightedValue,
                        aggregation.weightedAbsoluteScore);
            }
        }

        activeBranches.clear();
        settleResolvedBranches(branches, activeBranches, aggregation);
        for (BranchState branch : activeBranches) {
            BranchValue branchValue = evaluateBranchValue(path, schedule, branch, firePower);
            aggregation.weightedValue += branch.probability * branchValue.value;
            aggregation.weightedAbsoluteScore += branch.probability * branchValue.absoluteScore;
        }
        return new PowerSelection(
                firePower,
                aggregation.weightedValue,
                aggregation.weightedAbsoluteScore);
    }

    private PathEvaluationTemplate buildPathEvaluationTemplate(CandidatePath path,
                                                               List<PathWaveIntersection> pathIntersections) {
        if (path == null) {
            return new PathEvaluationTemplate(
                    Double.NaN,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }
        double horizonTime = explicitHorizonTime(path);
        List<ImmediateEvent> enemyShotEvents = new ArrayList<>();
        List<TailShotCredit> enemyTailShots = new ArrayList<>();
        EnemyContinuationPlan enemyPlan = buildEnemyContinuationPlan(path, pathIntersections);
        addEnemyShotEvents(enemyShotEvents, enemyTailShots, enemyPlan, horizonTime);
        List<ImmediateEvent> staticEvents = new ArrayList<>();
        addWallHitEvents(staticEvents, path);
        addRamEvents(staticEvents, path);
        return new PathEvaluationTemplate(horizonTime, enemyShotEvents, enemyTailShots, staticEvents);
    }

    private BranchedEventSchedule buildBranchedEventSchedule(CandidatePath path,
                                                             PathEvaluationTemplate template,
                                                             RobotSnapshot robotState,
                                                             int firstFiringTickOffset,
                                                             double firePower) {
        List<ImmediateEvent> shotEvents = new ArrayList<>();
        List<TailShotCredit> tailShots = new ArrayList<>();
        double horizonTime = template != null ? template.horizonTime : Double.NaN;
        addOurShotEvents(shotEvents, tailShots, path, robotState, firstFiringTickOffset, firePower, horizonTime);
        if (template != null) {
            shotEvents.addAll(template.enemyShotEvents);
            tailShots.addAll(template.enemyTailShots);
        }
        trimBranchedShotEvents(shotEvents, tailShots);
        List<ImmediateEvent> events = new ArrayList<>(shotEvents);
        if (template != null) {
            events.addAll(template.staticEvents);
        }
        sortImmediateEvents(events);
        return new BranchedEventSchedule(events, tailShots);
    }

    private void addOurShotEvents(List<ImmediateEvent> events,
                                  List<TailShotCredit> tailShots,
                                  CandidatePath path,
                                  RobotSnapshot robotState,
                                  int firstFiringTickOffset,
                                  double firePower,
                                  double horizonTime) {
        if (events == null || path == null || robotState == null || !(firePower >= MIN_FIRE_POWER)) {
            return;
        }
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return;
        }

        int explicitShotCount = 0;
        double nextShotTime = path.startTime + Math.max(0, firstFiringTickOffset);
        double firstHitProbability = estimateOurHitRateAtTime(path, nextShotTime, firePower);
        events.add(new ImmediateEvent(
                nextShotTime,
                0,
                ImmediateEventKind.OUR_SHOT,
                0.0,
                firstHitProbability,
                firePower,
                null,
                null,
                true,
                true));
        explicitShotCount++;
        if (!Double.isFinite(horizonTime)) {
            return;
        }
        double cadenceTicks = continuationCadenceTicks(firePower);
        if (!(cadenceTicks > 0.0)) {
            return;
        }
        double windowStartTime = nextShotTime;
        nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON
                && explicitShotCount < BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS) {
            double hitProbability = estimateOurHitRateAtTime(path, nextShotTime, firePower);
            events.add(new ImmediateEvent(
                    nextShotTime,
                    0,
                    ImmediateEventKind.OUR_SHOT,
                    0.0,
                    hitProbability,
                    firePower,
                    null,
                    null,
                    true,
                    true));
            explicitShotCount++;
            windowStartTime = nextShotTime;
            nextShotTime += cadenceTicks;
        }
        appendTailShotCredit(
                tailShots,
                true,
                firePower,
                estimateOurHitRateAtTime(path, windowStartTime, firePower),
                true,
                true,
                tailShotWeight(windowStartTime, cadenceTicks, cadenceTicks, horizonTime));
    }

    private EnemyContinuationPlan buildEnemyContinuationPlan(CandidatePath path,
                                                             List<PathWaveIntersection> pathIntersections) {
        if (path == null || pathIntersections == null || pathIntersections.isEmpty()) {
            return null;
        }
        List<EnemyActualShot> actualShots = new ArrayList<>();
        for (PathWaveIntersection intersection : pathIntersections) {
            if (intersection == null || intersection.wave == null || !intersection.wave.isEnemy) {
                continue;
            }
            double proxyFireTime = explicitWavePassTime(path, intersection.wave);
            if (!Double.isFinite(proxyFireTime)) {
                continue;
            }
            actualShots.add(new EnemyActualShot(
                    proxyFireTime,
                    Math.round(proxyFireTime),
                    intersection.wave.getFirepower(),
                    estimateEnemyHitRateAtTime(path, proxyFireTime, intersection.wave.getFirepower()),
                    intersection.wave));
        }
        if (actualShots.isEmpty()) {
            return null;
        }
        actualShots.sort((a, b) -> {
            int timeCompare = Double.compare(a.eventTime, b.eventTime);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Long.compare(a.fireTime, b.fireTime);
        });
        EnemyActualShot firstShot = actualShots.get(0);
        double templateCadenceTicks = continuationCadenceTicks(firstShot.shotPower);
        double continuationWindowStartTime = firstShot.eventTime;
        double firstContinuationCadenceTicks = templateCadenceTicks;
        if (actualShots.size() >= 2) {
            EnemyActualShot secondShot = actualShots.get(1);
            double observedCadenceTicks = secondShot.fireTime > firstShot.fireTime
                    ? secondShot.fireTime - firstShot.fireTime
                    : Double.NaN;
            continuationWindowStartTime = secondShot.eventTime;
            firstContinuationCadenceTicks = observedCadenceTicks > 0.0
                    ? observedCadenceTicks
                    : templateCadenceTicks;
        }
        return new EnemyContinuationPlan(
                actualShots,
                firstShot.shotPower,
                firstShot.hitProbability,
                templateCadenceTicks,
                continuationWindowStartTime,
                firstContinuationCadenceTicks);
    }

    private static void addEnemyShotEvents(List<ImmediateEvent> events,
                                           List<TailShotCredit> tailShots,
                                           EnemyContinuationPlan plan,
                                           double horizonTime) {
        if (plan == null) {
            return;
        }
        int explicitShotCount = 0;
        int actualShotIndex = 0;
        for (; actualShotIndex < plan.actualShots.size()
                && explicitShotCount < BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS;
             actualShotIndex++) {
            EnemyActualShot actualShot = plan.actualShots.get(actualShotIndex);
            events.add(new ImmediateEvent(
                    actualShot.eventTime,
                    1,
                    ImmediateEventKind.ENEMY_SHOT,
                    0.0,
                    actualShot.hitProbability,
                    actualShot.shotPower,
                    actualShot.wave,
                    null,
                    false,
                    false));
            explicitShotCount++;
        }
        if (actualShotIndex < plan.actualShots.size()) {
            for (int i = actualShotIndex; i < plan.actualShots.size(); i++) {
                EnemyActualShot actualShot = plan.actualShots.get(i);
                appendTailShotCredit(
                        tailShots,
                        false,
                        actualShot.shotPower,
                        actualShot.hitProbability,
                        false,
                        false,
                        1.0);
            }
            EnemyActualShot latestActualShot = latestActualShotByFireTime(plan.actualShots);
            if (latestActualShot == null) {
                return;
            }
            appendTailShotCredit(
                    tailShots,
                    false,
                    plan.templatePower,
                    plan.templateHitProbability,
                    true,
                    true,
                    tailShotWeight(
                            latestActualShot.fireTime,
                            continuationCadenceTicks(latestActualShot.shotPower),
                            plan.templateCadenceTicks,
                            horizonTime));
            return;
        }
        if (!Double.isFinite(horizonTime)
                || !(plan.templateCadenceTicks > 0.0)
                || !(plan.firstContinuationCadenceTicks > 0.0)) {
            return;
        }
        double windowStartTime = plan.continuationWindowStartTime;
        double cadenceTicks = plan.firstContinuationCadenceTicks;
        double nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON
                && explicitShotCount < BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS) {
            events.add(new ImmediateEvent(
                    nextShotTime,
                    1,
                    ImmediateEventKind.ENEMY_SHOT,
                    0.0,
                    plan.templateHitProbability,
                    plan.templatePower,
                    null,
                    null,
                    true,
                    true));
            explicitShotCount++;
            windowStartTime = nextShotTime;
            cadenceTicks = plan.templateCadenceTicks;
            nextShotTime = windowStartTime + cadenceTicks;
        }
        appendTailShotCredit(
                tailShots,
                false,
                plan.templatePower,
                plan.templateHitProbability,
                true,
                true,
                tailShotWeight(windowStartTime, cadenceTicks, plan.templateCadenceTicks, horizonTime));
    }

    private static void addWallHitEvents(List<ImmediateEvent> events,
                                         CandidatePath path) {
        if (events == null || path == null || path.trajectory == null) {
            return;
        }
        for (int i = 1; i < path.trajectory.length(); i++) {
            PhysicsUtil.PositionState state = path.trajectory.stateAt(i);
            if (state.wallHitDamage > 0.0) {
                events.add(new ImmediateEvent(
                        path.startTime + i,
                        3,
                        ImmediateEventKind.WALL_HIT,
                        state.wallHitDamage,
                        1.0,
                        0.0,
                        null,
                        null,
                        false,
                        false));
            }
        }
    }

    private static void addRamEvents(List<ImmediateEvent> events,
                                     CandidatePath path) {
        if (events == null || path == null || path.ramEvents == null) {
            return;
        }
        for (RamEvent ramEvent : path.ramEvents) {
            if (ramEvent == null) {
                continue;
            }
            events.add(new ImmediateEvent(
                    ramEvent.time,
                    2,
                    ImmediateEventKind.RAM,
                    1.0,
                    1.0,
                    0.0,
                    null,
                    ramEvent,
                    false,
                    false));
        }
    }

    private static void trimBranchedShotEvents(List<ImmediateEvent> shotEvents,
                                               List<TailShotCredit> tailShots) {
        if (shotEvents == null || shotEvents.size() <= BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS) {
            return;
        }
        sortImmediateEvents(shotEvents);
        for (int i = BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS; i < shotEvents.size(); i++) {
            ImmediateEvent event = shotEvents.get(i);
            if (event == null) {
                continue;
            }
            appendTailShotCredit(
                    tailShots,
                    event.kind == ImmediateEventKind.OUR_SHOT,
                    event.shotPower,
                    event.probability,
                    event.deductEnergyCost,
                    event.requireShooterAlive,
                    1.0);
        }
        shotEvents.subList(BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS, shotEvents.size()).clear();
    }

    private static void appendTailShotCredit(List<TailShotCredit> tailShots,
                                             boolean ourSide,
                                             double shotPower,
                                             double hitProbability,
                                             boolean deductEnergyCost,
                                             boolean requireShooterAlive,
                                             double shotWeight) {
        if (tailShots == null || !(shotWeight > 0.0)) {
            return;
        }
        tailShots.add(new TailShotCredit(
                ourSide,
                shotPower,
                hitProbability,
                deductEnergyCost,
                requireShooterAlive,
                shotWeight));
    }

    private static EnemyActualShot latestActualShotByFireTime(List<EnemyActualShot> actualShots) {
        EnemyActualShot latestShot = null;
        for (EnemyActualShot actualShot : actualShots) {
            if (actualShot == null) {
                continue;
            }
            if (latestShot == null || actualShot.fireTime > latestShot.fireTime) {
                latestShot = actualShot;
            }
        }
        return latestShot;
    }

    private static double tailShotWeight(double windowStartTime,
                                         double firstCadenceTicks,
                                         double steadyCadenceTicks,
                                         double horizonTime) {
        if (!(firstCadenceTicks > 0.0)
                || !(steadyCadenceTicks > 0.0)
                || !(horizonTime > windowStartTime)) {
            return 0.0;
        }
        double shotWeight = 0.0;
        double cadenceTicks = firstCadenceTicks;
        double nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON) {
            shotWeight += 1.0;
            windowStartTime = nextShotTime;
            cadenceTicks = steadyCadenceTicks;
            nextShotTime = windowStartTime + cadenceTicks;
        }
        return shotWeight + overlapFraction(windowStartTime, cadenceTicks, horizonTime);
    }

    private static double overlapFraction(double windowStartTime,
                                          double cadenceTicks,
                                          double horizonTime) {
        if (!(cadenceTicks > 0.0) || !(horizonTime > windowStartTime)) {
            return 0.0;
        }
        double overlapStart = windowStartTime;
        double overlapEnd = Math.min(windowStartTime + cadenceTicks, horizonTime);
        if (!(overlapEnd > overlapStart)) {
            return 0.0;
        }
        return (overlapEnd - overlapStart) / cadenceTicks;
    }

    private double estimateOurHitRateAtTime(CandidatePath path,
                                            double shotTime,
                                            double firePower) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return 0.0;
        }
        PhysicsUtil.PositionState ourState = pathStateAtTime(path, shotTime);
        EnemyInfo.PredictedPosition enemyState = enemy.predictPositionAtTime(
                Math.round(shotTime),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return info.getBulletPowerHitRateTracker().estimateHitRate(
                ourState.x,
                ourState.y,
                enemyState.x,
                enemyState.y,
                Wave.bulletSpeed(firePower),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    private double estimateEnemyHitRateAtTime(CandidatePath path,
                                              double shotTime,
                                              double firePower) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return 0.0;
        }
        PhysicsUtil.PositionState ourState = pathStateAtTime(path, shotTime);
        EnemyInfo.PredictedPosition enemyState = enemy.predictPositionAtTime(
                Math.round(shotTime),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
        return info.getEnemyBulletHitRateTracker().estimateHitRate(
                enemyState.x,
                enemyState.y,
                ourState.x,
                ourState.y,
                Wave.bulletSpeed(firePower),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    private BranchValue evaluateBranchValue(CandidatePath path,
                                            BranchedEventSchedule schedule,
                                            BranchState branch,
                                            double continuationFirePower) {
        if (branch.roundResolved) {
            return resolvedBranchValue(branch);
        }
        ContinuationDeltas continuationDeltas = estimateBoundedContinuationDeltas(schedule, branch);
        double projectedOurScore = branch.ourScore + continuationDeltas.expectedScoreGained;
        double projectedOpponentScore = branch.opponentScore + continuationDeltas.expectedScoreConceded;
        double projectedOurEnergy = Math.max(0.0, branch.ourEnergy + continuationDeltas.energyDelta);
        double projectedEnemyEnergy = Math.max(
                0.0,
                branch.enemyEnergy
                        - continuationDeltas.expectedEnemyEnergyLoss
                        + continuationDeltas.expectedEnemyEnergyGain
                        - continuationDeltas.expectedEnemyEnergyDrain);
        double projectedOurCreditedDamage = branch.ourCreditedDamageOnEnemy
                + Math.max(0.0, continuationDeltas.expectedEnemyEnergyLoss);
        double projectedEnemyCreditedDamage = branch.enemyCreditedDamageOnUs
                + Math.max(0.0, continuationDeltas.expectedOpponentCreditedDamage);
        PhysicsUtil.PositionState finalOurState = path.trajectory.stateAt(path.trajectory.length() - 1);
        long finalTime = path.startTime + Math.max(0, path.trajectory.length() - 1);
        EnemyInfo.PredictedPosition finalEnemyState = predictEnemyPositionAt(finalTime);
        double ourPower = sanitizeContinuationPower(continuationFirePower, projectedOurEnergy);
        double enemyPower = sanitizeContinuationPower(
                assumedEnemyContinuationPower(projectedEnemyEnergy),
                projectedEnemyEnergy);
        double ourHitRate = finalEnemyState != null && ourPower >= MIN_FIRE_POWER
                ? info.getBulletPowerHitRateTracker().estimateHitRate(
                        finalOurState.x,
                        finalOurState.y,
                        finalEnemyState.x,
                        finalEnemyState.y,
                        Wave.bulletSpeed(ourPower),
                        info.getBattlefieldWidth(),
                        info.getBattlefieldHeight())
                : 0.0;
        double enemyHitRate = finalEnemyState != null && enemyPower >= MIN_FIRE_POWER
                ? info.getEnemyBulletHitRateTracker().estimateHitRate(
                        finalEnemyState.x,
                        finalEnemyState.y,
                        finalOurState.x,
                        finalOurState.y,
                        Wave.bulletSpeed(enemyPower),
                        info.getBattlefieldWidth(),
                        info.getBattlefieldHeight())
                : 0.0;

        double value = evaluateContinuationBranchValue(
                projectedOurScore,
                projectedOpponentScore,
                projectedOurEnergy,
                projectedEnemyEnergy,
                projectedOurCreditedDamage,
                projectedEnemyCreditedDamage,
                ourPower,
                ourHitRate,
                enemyPower,
                enemyHitRate);
        return new BranchValue(value, projectedOurScore - projectedOpponentScore);
    }

    private double evaluateContinuationBranchValue(double ourScore,
                                                   double opponentScore,
                                                   double ourEnergy,
                                                   double enemyEnergy,
                                                   double ourCreditedDamageOnEnemy,
                                                   double enemyCreditedDamageOnUs,
                                                   double ourPower,
                                                   double ourHitRate,
                                                   double enemyPower,
                                                   double enemyHitRate) {
        double clampedOurEnergy = Math.max(0.0, ourEnergy);
        double clampedEnemyEnergy = Math.max(0.0, enemyEnergy);
        if (!isEndOfRoundBonusEvEnabledForState()) {
            double denominator = ourScore + opponentScore;
            return denominator > 0.0
                    ? ourScore / denominator
                    : ourScore - opponentScore + 0.1 * (clampedOurEnergy - clampedEnemyEnergy);
        }
        double winProbability = estimateWinProbability(clampedOurEnergy, clampedEnemyEnergy);
        TerminalScoreEstimate terminalEstimate = estimateTerminalScoreAdds(
                ourCreditedDamageOnEnemy,
                enemyCreditedDamageOnUs,
                clampedOurEnergy,
                clampedEnemyEnergy,
                winProbability,
                ourPower,
                ourHitRate,
                enemyPower,
                enemyHitRate);
        double denominator = ourScore + opponentScore
                + terminalEstimate.ourTerminalAdd
                + terminalEstimate.opponentTerminalAdd;
        if (denominator <= 0.0) {
            return winProbability;
        }
        return (ourScore + terminalEstimate.ourTerminalAdd) / denominator;
    }

    private TerminalScoreEstimate estimateTerminalScoreAdds(double ourCreditedDamageOnEnemy,
                                                            double enemyCreditedDamageOnUs,
                                                            double ourEnergy,
                                                            double enemyEnergy,
                                                            double ourWinProbability,
                                                            double ourPower,
                                                            double ourHitRate,
                                                            double enemyPower,
                                                            double enemyHitRate) {
        if (!isEndOfRoundBonusEvEnabledForState()) {
            return new TerminalScoreEstimate(0.0, 0.0);
        }
        double clampedOurWinProbability = Math.max(0.0, Math.min(1.0, ourWinProbability));
        double clampedEnemyWinProbability = 1.0 - clampedOurWinProbability;
        double ourDamageRate = continuationBulletDamageRate(ourPower, ourHitRate);
        double enemyDamageRate = continuationBulletDamageRate(enemyPower, enemyHitRate);
        double ourNetSelfEnergyLossRate = continuationNetSelfEnergyLossRate(ourPower, ourHitRate);
        double enemyNetSelfEnergyLossRate = continuationNetSelfEnergyLossRate(enemyPower, enemyHitRate);
        double enemyLossRate = ourDamageRate + enemyNetSelfEnergyLossRate;
        double ourLossRate = enemyDamageRate + ourNetSelfEnergyLossRate;
        double fightDuration = terminalFightDuration(ourEnergy, ourLossRate, enemyEnergy, enemyLossRate);
        double extraOurCreditedDamage = expectedCreditedDamageOverDuration(ourDamageRate, fightDuration);
        double extraEnemyCreditedDamage = expectedCreditedDamageOverDuration(enemyDamageRate, fightDuration);
        double ourTerminalIfWin = RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1
                + RobocodeScoreUtil.bulletKillBonus(ourCreditedDamageOnEnemy + extraOurCreditedDamage);
        double enemyTerminalIfWin = RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1
                + RobocodeScoreUtil.bulletKillBonus(enemyCreditedDamageOnUs + extraEnemyCreditedDamage);
        return new TerminalScoreEstimate(
                clampedOurWinProbability * ourTerminalIfWin,
                clampedEnemyWinProbability * enemyTerminalIfWin);
    }

    private double estimateWinProbability(double ourEnergy, double enemyEnergy) {
        double survivalPrior = info.getRoundOutcomeProfile() != null
                ? info.getRoundOutcomeProfile().getSurvivalPrior()
                : 0.5;
        double clampedPrior = Math.max(
                BotConfig.ScoreMax.MIN_PRIOR_WIN_PROBABILITY,
                Math.min(BotConfig.ScoreMax.MAX_PRIOR_WIN_PROBABILITY, survivalPrior));
        return sigmoid(
                priorWeight(ourEnergy, enemyEnergy) * logit(clampedPrior)
                        + energyLeadTerm(ourEnergy, enemyEnergy));
    }

    private static double priorWeight(double ourEnergy, double enemyEnergy) {
        double totalEnergy = Math.max(0.0, ourEnergy) + Math.max(0.0, enemyEnergy);
        double energyRatio = Math.max(0.0, Math.min(1.0, totalEnergy / 200.0));
        return BotConfig.ScoreMax.DEFAULT_PRIOR_WEIGHT_FLOOR
                + (1.0 - BotConfig.ScoreMax.DEFAULT_PRIOR_WEIGHT_FLOOR)
                * Math.pow(energyRatio, BotConfig.ScoreMax.DEFAULT_PRIOR_WEIGHT_EXPONENT);
    }

    private static double energyLeadTerm(double ourEnergy, double enemyEnergy) {
        double clampedOurEnergy = Math.max(0.0, ourEnergy);
        double clampedEnemyEnergy = Math.max(0.0, enemyEnergy);
        double totalEnergy = clampedOurEnergy + clampedEnemyEnergy;
        double energyRatio = Math.max(0.0, Math.min(1.0, totalEnergy / 200.0));
        double lowEnergyUncertainty = BotConfig.ScoreMax.DEFAULT_LOW_ENERGY_UNCERTAINTY_SCALE * (1.0 - energyRatio);
        double leadDenominator = totalEnergy + BotConfig.ScoreMax.DEFAULT_ENERGY_LEAD_OFFSET + lowEnergyUncertainty;
        return BotConfig.ScoreMax.DEFAULT_ENERGY_LEAD_SCALE
                * (clampedOurEnergy - clampedEnemyEnergy)
                / leadDenominator;
    }

    private static double logit(double probability) {
        return Math.log(probability / (1.0 - probability));
    }

    private static double sigmoid(double x) {
        if (x >= 0.0) {
            double e = Math.exp(-x);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(x);
        return e / (1.0 + e);
    }

    private boolean isEndOfRoundBonusEvEnabledForState() {
        EnemyInfo enemy = info.getEnemy();
        return BotConfig.ScoreMax.ENABLE_END_OF_ROUND_BONUS_EV
                && enemy != null
                && enemy.alive
                && enemy.seenThisRound;
    }

    private double currentEnemyEnergyForScoring() {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return 0.0;
        }
        return Math.max(0.0, enemy.energy);
    }

    private EnemyInfo.PredictedPosition predictEnemyPositionAt(long time) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return null;
        }
        return enemy.predictPositionAtTime(
                time,
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    private double assumedEnemyContinuationPower(double enemyEnergy) {
        EnemyInfo enemy = info.getEnemy();
        double lastDetectedBulletPower = enemy != null ? enemy.lastDetectedBulletPower : Double.NaN;
        double assumedPower = Double.isFinite(lastDetectedBulletPower)
                ? lastDetectedBulletPower
                : BotConfig.ScoreMax.DEFAULT_ENEMY_CONTINUATION_POWER;
        return Math.min(assumedPower, Math.max(0.0, enemyEnergy));
    }

    private static double sanitizeContinuationPower(double power, double availableEnergy) {
        if (!(power >= MIN_FIRE_POWER) || !(availableEnergy >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.min(3.0, Math.min(power, availableEnergy));
    }

    private static double continuationCadenceTicks(double power) {
        return Math.max(1.0, (1.0 + power / 5.0) / 0.1);
    }

    private static double continuationBulletDamageRate(double power, double hitRate) {
        if (!(power >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return hitRate * Rules.getBulletDamage(power) / continuationCadenceTicks(power);
    }

    private static double continuationNetSelfEnergyLossRate(double power, double hitRate) {
        if (!(power >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return (power - 3.0 * hitRate * power) / continuationCadenceTicks(power);
    }

    private static double terminalFightDuration(double ourEnergy,
                                                double ourLossRate,
                                                double enemyEnergy,
                                                double enemyLossRate) {
        return Math.min(timeToZeroEnergy(ourEnergy, ourLossRate), timeToZeroEnergy(enemyEnergy, enemyLossRate));
    }

    private static double timeToZeroEnergy(double energy, double lossRate) {
        if (!(energy > 0.0)) {
            return 0.0;
        }
        if (!(lossRate > MIN_TERMINAL_RATE)) {
            return Double.POSITIVE_INFINITY;
        }
        return energy / lossRate;
    }

    private static double expectedCreditedDamageOverDuration(double damageRate,
                                                             double duration) {
        if (!(damageRate > 0.0) || !(duration > 0.0) || !Double.isFinite(duration)) {
            return 0.0;
        }
        return damageRate * duration;
    }

    private ContinuationDeltas estimateBoundedContinuationDeltas(BranchedEventSchedule schedule,
                                                                 BranchState branch) {
        ContinuationDeltas deltas = new ContinuationDeltas();
        if (schedule == null || branch == null || !branch.ourAlive || !branch.enemyAlive || branch.roundResolved) {
            return deltas;
        }
        for (TailShotCredit tailShot : schedule.tailShots) {
            addTailShotCredit(deltas, tailShot, branch);
        }
        return deltas;
    }

    private void addTailShotCredit(ContinuationDeltas deltas,
                                   TailShotCredit tailShot,
                                   BranchState branch) {
        if (deltas == null || tailShot == null || branch == null) {
            return;
        }
        if (!(tailShot.shotWeight > 0.0)) {
            return;
        }
        ContinuationEventEffects effects = tailShot.ourSide
                ? buildOurContinuationEventEffects(tailShot, branch.ourEnergy, branch.enemyEnergy)
                : buildEnemyContinuationEventEffects(tailShot, branch.ourEnergy, branch.enemyEnergy);
        if (effects == null) {
            return;
        }
        deltas.addScaled(effects, tailShot.shotWeight);
    }

    private ContinuationEventEffects buildOurContinuationEventEffects(TailShotCredit tailShot,
                                                                      double ourEnergy,
                                                                      double enemyEnergy) {
        if (tailShot == null
                || !(tailShot.shotPower >= MIN_FIRE_POWER)
                || (tailShot.requireShooterAlive && !(ourEnergy > 0.0))
                || (tailShot.deductEnergyCost && ourEnergy + 1e-9 < tailShot.shotPower)
                || !(enemyEnergy > 0.0)) {
            return null;
        }
        double hitProbability = Math.max(0.0, Math.min(1.0, tailShot.hitProbability));
        double scoreGain = hitProbability * Math.min(Rules.getBulletDamage(tailShot.shotPower), enemyEnergy);
        double ourEnergyDelta = (tailShot.deductEnergyCost ? -tailShot.shotPower : 0.0)
                + hitProbability * 3.0 * tailShot.shotPower;
        return new ContinuationEventEffects(
                scoreGain,
                0.0,
                0.0,
                0.0,
                ourEnergyDelta,
                scoreGain,
                0.0);
    }

    private ContinuationEventEffects buildEnemyContinuationEventEffects(TailShotCredit tailShot,
                                                                        double ourEnergy,
                                                                        double enemyEnergy) {
        if (tailShot == null
                || !(tailShot.shotPower >= MIN_FIRE_POWER)
                || (tailShot.requireShooterAlive && !(enemyEnergy > 0.0))
                || (tailShot.deductEnergyCost && enemyEnergy + 1e-9 < tailShot.shotPower)
                || !(ourEnergy > 0.0)) {
            return null;
        }
        double hitProbability = Math.max(0.0, Math.min(1.0, tailShot.hitProbability));
        double scoreTaken = hitProbability * Math.min(Rules.getBulletDamage(tailShot.shotPower), ourEnergy);
        double expectedEnemyEnergyGain = hitProbability * 3.0 * tailShot.shotPower;
        return new ContinuationEventEffects(
                0.0,
                scoreTaken,
                expectedEnemyEnergyGain,
                tailShot.deductEnergyCost ? tailShot.shotPower : 0.0,
                -scoreTaken,
                0.0,
                scoreTaken);
    }

    private void applyImmediateEvent(List<BranchState> nextBranches,
                                     BranchState branch,
                                     ImmediateEvent event) {
        if (nextBranches == null || branch == null || event == null || !(branch.probability > 0.0)) {
            return;
        }
        switch (event.kind) {
            case OUR_SHOT:
            case ENEMY_SHOT:
                branchShotEvent(nextBranches, branch, event);
                break;
            case WALL_HIT:
                applyWallHitEvent(branch, event.value);
                nextBranches.add(branch);
                break;
            case RAM:
                applyRamEvent(branch, event.ramEvent);
                nextBranches.add(branch);
                break;
            default:
                throw new IllegalStateException("Unhandled event kind: " + event.kind);
        }
    }

    private void branchShotEvent(List<BranchState> nextBranches,
                                 BranchState branch,
                                 ImmediateEvent event) {
        if (nextBranches == null || branch == null || event == null || !canResolveShotEvent(branch, event)) {
            if (nextBranches != null && branch != null && event != null) {
                nextBranches.add(branch);
            }
            return;
        }
        double clampedHitProbability = Math.max(0.0, Math.min(1.0, event.probability));
        double missProbability = 1.0 - clampedHitProbability;
        if (missProbability > 0.0) {
            BranchState missBranch = branch.copyWithProbability(branch.probability * missProbability);
            applyShotOutcome(missBranch, event, false);
            nextBranches.add(missBranch);
        }
        if (clampedHitProbability <= 0.0) {
            return;
        }
        BranchState hitBranch = branch.copyWithProbability(branch.probability * clampedHitProbability);
        applyShotOutcome(hitBranch, event, true);
        nextBranches.add(hitBranch);
    }

    private boolean canResolveShotEvent(BranchState branch,
                                        ImmediateEvent event) {
        if (branch == null || event == null || !(event.shotPower >= MIN_FIRE_POWER)) {
            return false;
        }
        if (event.kind == ImmediateEventKind.OUR_SHOT) {
            if (event.requireShooterAlive && !branch.ourAlive) {
                return false;
            }
            if (event.deductEnergyCost && branch.ourEnergy + 1e-9 < event.shotPower) {
                return false;
            }
            return branch.enemyEnergy > 0.0 || event.deductEnergyCost;
        }
        if (!(branch.ourEnergy > 0.0)) {
            return false;
        }
        if (event.requireShooterAlive && !branch.enemyAlive) {
            return false;
        }
        if (event.deductEnergyCost && branch.enemyEnergy + 1e-9 < event.shotPower) {
            return false;
        }
        return true;
    }

    private void applyShotOutcome(BranchState branch,
                                  ImmediateEvent event,
                                  boolean hit) {
        if (branch == null || event == null) {
            return;
        }
        boolean ourSide = event.kind == ImmediateEventKind.OUR_SHOT;
        boolean shooterAliveBeforeCost = ourSide ? branch.ourAlive : branch.enemyAlive;
        if (event.deductEnergyCost) {
            if (ourSide) {
                branch.ourEnergy = Math.max(0.0, branch.ourEnergy - event.shotPower);
            } else {
                branch.enemyEnergy = Math.max(0.0, branch.enemyEnergy - event.shotPower);
            }
            updateAliveStates(branch);
        }
        boolean shooterAliveAfterCost = ourSide ? branch.ourAlive : branch.enemyAlive;
        if (!hit) {
            return;
        }
        double targetEnergyBeforeHit = ourSide ? branch.enemyEnergy : branch.ourEnergy;
        RobocodeScoreUtil.scoreBulletHit(
                event.shotPower,
                targetEnergyBeforeHit,
                ourSide ? branch.ourCreditedDamageOnEnemy : branch.enemyCreditedDamageOnUs,
                ourSide ? branch.ourKillBonusApplied : branch.enemyKillBonusApplied,
                hitScoreScratch);
        if (!(hitScoreScratch.creditedDamage > 0.0)) {
            return;
        }
        if (ourSide) {
            branch.ourScore += hitScoreScratch.scoreDelta;
            branch.ourCreditedDamageOnEnemy = hitScoreScratch.cumulativeCreditedDamage;
            branch.ourKillBonusApplied = hitScoreScratch.killBonusApplied;
            branch.enemyEnergy = Math.max(0.0, branch.enemyEnergy - hitScoreScratch.creditedDamage);
            if (shooterAliveBeforeCost && shooterAliveAfterCost) {
                branch.ourEnergy += 3.0 * event.shotPower;
            }
        } else {
            branch.opponentScore += hitScoreScratch.scoreDelta;
            branch.enemyCreditedDamageOnUs = hitScoreScratch.cumulativeCreditedDamage;
            branch.enemyKillBonusApplied = hitScoreScratch.killBonusApplied;
            branch.ourEnergy = Math.max(0.0, branch.ourEnergy - hitScoreScratch.creditedDamage);
            if (shooterAliveBeforeCost && shooterAliveAfterCost) {
                branch.enemyEnergy += 3.0 * event.shotPower;
            }
        }
        updateAliveStates(branch);
    }

    private void applyWallHitEvent(BranchState branch,
                                   double wallDamage) {
        if (branch == null || !(wallDamage > 0.0) || !branch.ourAlive) {
            return;
        }
        branch.ourEnergy = Math.max(0.0, branch.ourEnergy - wallDamage);
        updateAliveStates(branch);
    }

    private void applyRamEvent(BranchState branch,
                               RamEvent ramEvent) {
        if (branch == null || ramEvent == null || !branch.ourAlive || !branch.enemyAlive) {
            return;
        }
        branch.ourScore += ramEvent.ourScoreDelta;
        branch.opponentScore += ramEvent.opponentScoreDelta;
        branch.ourEnergy = Math.max(0.0, branch.ourEnergy - Math.max(0.0, ramEvent.ourEnergyLoss));
        branch.enemyEnergy = Math.max(0.0, branch.enemyEnergy - Math.max(0.0, ramEvent.enemyEnergyLoss));
        branch.ourCreditedDamageOnEnemy += Math.max(0.0, ramEvent.ourCreditedDamageOnEnemyDelta);
        branch.enemyCreditedDamageOnUs += Math.max(0.0, ramEvent.enemyCreditedDamageOnUsDelta);
        branch.ourKillBonusApplied = branch.ourKillBonusApplied || ramEvent.ourKillBonusApplied;
        branch.enemyKillBonusApplied = branch.enemyKillBonusApplied || ramEvent.enemyKillBonusApplied;
        updateAliveStates(branch);
    }

    private void updateAliveStates(BranchState branch) {
        if (branch == null) {
            return;
        }
        boolean previousOurAlive = branch.ourAlive;
        boolean previousEnemyAlive = branch.enemyAlive;
        boolean currentOurAlive = branch.ourEnergy > 1e-9;
        boolean currentEnemyAlive = branch.enemyEnergy > 1e-9;
        if (previousOurAlive && !currentOurAlive && currentEnemyAlive) {
            branch.opponentScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        }
        if (previousEnemyAlive && !currentEnemyAlive && currentOurAlive) {
            branch.ourScore += RobocodeScoreUtil.SURVIVAL_AND_LAST_BONUS_1V1;
        }
        branch.ourAlive = currentOurAlive;
        branch.enemyAlive = currentEnemyAlive;
        branch.roundResolved = !currentOurAlive || !currentEnemyAlive;
    }

    private void settleResolvedBranches(List<BranchState> source,
                                        List<BranchState> unresolvedOut,
                                        Aggregation aggregation) {
        if (source == null || unresolvedOut == null || aggregation == null) {
            return;
        }
        for (BranchState branch : source) {
            if (branch == null || !(branch.probability > 0.0)) {
                continue;
            }
            if (branch.roundResolved) {
                BranchValue branchValue = resolvedBranchValue(branch);
                aggregation.weightedValue += branch.probability * branchValue.value;
                aggregation.weightedAbsoluteScore += branch.probability * branchValue.absoluteScore;
                continue;
            }
            unresolvedOut.add(branch);
        }
    }

    private static BranchValue resolvedBranchValue(BranchState branch) {
        return new BranchValue(
                exactShareValue(branch.ourScore, branch.opponentScore),
                branch.ourScore - branch.opponentScore);
    }

    private static double exactShareValue(double ourScore,
                                          double opponentScore) {
        double denominator = ourScore + opponentScore;
        if (denominator > 0.0) {
            return ourScore / denominator;
        }
        return ourScore - opponentScore;
    }

    private static boolean sameEventTime(double a, double b) {
        return Math.abs(a - b) <= EVENT_TIME_EPSILON;
    }

    private static boolean isBetterSelection(PowerSelection candidate,
                                             PowerSelection incumbent) {
        if (candidate == null) {
            return false;
        }
        if (incumbent == null) {
            return true;
        }
        if (candidate.value > incumbent.value + 1e-12) {
            return true;
        }
        if (candidate.value < incumbent.value - 1e-12) {
            return false;
        }
        if (candidate.absoluteScore > incumbent.absoluteScore + 1e-12) {
            return true;
        }
        if (candidate.absoluteScore < incumbent.absoluteScore - 1e-12) {
            return false;
        }
        return candidate.firePower > incumbent.firePower + 1e-12;
    }

    private static double maxCandidateOffensivePower(double availableEnergy,
                                                     double enemyEnergyForScoring) {
        if (!(availableEnergy >= MIN_FIRE_POWER)) {
            return 0.0;
        }
        return Math.min(
                3.0,
                Math.min(availableEnergy, PhysicsUtil.requiredBulletPowerForDamage(enemyEnergyForScoring)));
    }

    private static List<Double> buildCandidateOffensivePowers(double maxPower) {
        List<Double> powers = new ArrayList<>();
        if (!(maxPower >= MIN_FIRE_POWER)) {
            return powers;
        }
        if (Math.abs(maxPower - MIN_FIRE_POWER) <= 1e-6) {
            powers.add(MIN_FIRE_POWER);
            return powers;
        }
        for (int i = 0; i < BotConfig.ScoreMax.GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT; i++) {
            double fraction = i / (double) (BotConfig.ScoreMax.GLOBAL_OFFENSIVE_POWER_SAMPLE_COUNT - 1);
            double power = MIN_FIRE_POWER + (maxPower - MIN_FIRE_POWER) * fraction;
            appendUniquePower(powers, power);
        }
        return powers;
    }

    private static void appendUniquePower(List<Double> powers, double power) {
        for (double existing : powers) {
            if (Math.abs(existing - power) <= 1e-6) {
                return;
            }
        }
        powers.add(power);
    }

    private static double explicitHorizonTime(CandidatePath path) {
        double lastPassTime = Double.NEGATIVE_INFINITY;
        if (path == null || path.scoringWaves == null) {
            return Double.NaN;
        }
        for (Wave wave : path.scoringWaves) {
            if (wave == null || !wave.isEnemy) {
                continue;
            }
            double passTime = explicitWavePassTime(path, wave);
            if (Double.isFinite(passTime) && passTime > lastPassTime) {
                lastPassTime = passTime;
            }
        }
        return Double.isFinite(lastPassTime) ? lastPassTime : Double.NaN;
    }

    private static double explicitWavePassTime(CandidatePath path, Wave wave) {
        if (path == null || path.trajectory == null || wave == null) {
            return Double.NaN;
        }
        PhysicsUtil.PositionState[] states = path.trajectory.states;
        for (int i = 0; i < states.length; i++) {
            long time = path.startTime + i;
            PhysicsUtil.PositionState state = states[i];
            if (wave.hasPassed(state.x, state.y, time)) {
                return time;
            }
        }
        PhysicsUtil.PositionState lastState = states[states.length - 1];
        double maxDistance = wave.maxDistanceToBody(lastState.x, lastState.y);
        return Math.max(
                path.startTime + states.length - 1,
                Math.floor(maxDistance / wave.speed + wave.fireTime) + 1.0);
    }

    private static PhysicsUtil.PositionState pathStateAtTime(CandidatePath path, double time) {
        int tickOffset = (int) Math.round(time - path.startTime);
        return path.trajectory.stateAt(tickOffset);
    }

    private static void sortImmediateEvents(List<ImmediateEvent> events) {
        if (events == null || events.size() <= 1) {
            return;
        }
        events.sort((a, b) -> {
            int timeCompare = Double.compare(a.time, b.time);
            if (timeCompare != 0) {
                return timeCompare;
            }
            return Integer.compare(a.priority, b.priority);
        });
    }

    static final class PowerSelection {
        final double firePower;
        final double value;
        final double absoluteScore;

        PowerSelection(double firePower, double value, double absoluteScore) {
            this.firePower = firePower;
            this.value = value;
            this.absoluteScore = absoluteScore;
        }
    }

    private static final class TerminalScoreEstimate {
        final double ourTerminalAdd;
        final double opponentTerminalAdd;

        TerminalScoreEstimate(double ourTerminalAdd, double opponentTerminalAdd) {
            this.ourTerminalAdd = ourTerminalAdd;
            this.opponentTerminalAdd = opponentTerminalAdd;
        }
    }

    private static final class BranchValue {
        final double value;
        final double absoluteScore;

        BranchValue(double value, double absoluteScore) {
            this.value = value;
            this.absoluteScore = absoluteScore;
        }
    }

    private static final class Aggregation {
        double weightedValue;
        double weightedAbsoluteScore;
    }

    private static final class PathEvaluationTemplate {
        final double horizonTime;
        final List<ImmediateEvent> enemyShotEvents;
        final List<TailShotCredit> enemyTailShots;
        final List<ImmediateEvent> staticEvents;

        PathEvaluationTemplate(double horizonTime,
                               List<ImmediateEvent> enemyShotEvents,
                               List<TailShotCredit> enemyTailShots,
                               List<ImmediateEvent> staticEvents) {
            this.horizonTime = horizonTime;
            this.enemyShotEvents = enemyShotEvents;
            this.enemyTailShots = enemyTailShots;
            this.staticEvents = staticEvents;
        }
    }

    private static final class BranchedEventSchedule {
        final List<ImmediateEvent> events;
        final List<TailShotCredit> tailShots;

        BranchedEventSchedule(List<ImmediateEvent> events,
                              List<TailShotCredit> tailShots) {
            this.events = events;
            this.tailShots = tailShots;
        }
    }

    private static final class TailShotCredit {
        final boolean ourSide;
        final double shotPower;
        final double hitProbability;
        final boolean deductEnergyCost;
        final boolean requireShooterAlive;
        final double shotWeight;

        TailShotCredit(boolean ourSide,
                       double shotPower,
                       double hitProbability,
                       boolean deductEnergyCost,
                       boolean requireShooterAlive,
                       double shotWeight) {
            this.ourSide = ourSide;
            this.shotPower = shotPower;
            this.hitProbability = hitProbability;
            this.deductEnergyCost = deductEnergyCost;
            this.requireShooterAlive = requireShooterAlive;
            this.shotWeight = shotWeight;
        }
    }

    private static final class EnemyActualShot {
        final double eventTime;
        final long fireTime;
        final double shotPower;
        final double hitProbability;
        final Wave wave;

        EnemyActualShot(double eventTime,
                        long fireTime,
                        double shotPower,
                        double hitProbability,
                        Wave wave) {
            this.eventTime = eventTime;
            this.fireTime = fireTime;
            this.shotPower = shotPower;
            this.hitProbability = hitProbability;
            this.wave = wave;
        }
    }

    private static final class EnemyContinuationPlan {
        final List<EnemyActualShot> actualShots;
        final double templatePower;
        final double templateHitProbability;
        final double templateCadenceTicks;
        final double continuationWindowStartTime;
        final double firstContinuationCadenceTicks;

        EnemyContinuationPlan(List<EnemyActualShot> actualShots,
                              double templatePower,
                              double templateHitProbability,
                              double templateCadenceTicks,
                              double continuationWindowStartTime,
                              double firstContinuationCadenceTicks) {
            this.actualShots = actualShots;
            this.templatePower = templatePower;
            this.templateHitProbability = templateHitProbability;
            this.templateCadenceTicks = templateCadenceTicks;
            this.continuationWindowStartTime = continuationWindowStartTime;
            this.firstContinuationCadenceTicks = firstContinuationCadenceTicks;
        }
    }

    private static final class ContinuationDeltas {
        double expectedScoreGained;
        double expectedScoreConceded;
        double expectedEnemyEnergyGain;
        double expectedEnemyEnergyDrain;
        double energyDelta;
        double expectedEnemyEnergyLoss;
        double expectedOpponentCreditedDamage;

        void addScaled(ContinuationEventEffects effects, double scale) {
            if (effects == null || !(scale > 0.0)) {
                return;
            }
            expectedScoreGained += scale * effects.expectedScoreGained;
            expectedScoreConceded += scale * effects.expectedScoreConceded;
            expectedEnemyEnergyGain += scale * effects.expectedEnemyEnergyGain;
            expectedEnemyEnergyDrain += scale * effects.expectedEnemyEnergyDrain;
            energyDelta += scale * effects.energyDelta;
            expectedEnemyEnergyLoss += scale * effects.expectedEnemyEnergyLoss;
            expectedOpponentCreditedDamage += scale * effects.expectedOpponentCreditedDamage;
        }
    }

    private static final class ContinuationEventEffects {
        final double expectedScoreGained;
        final double expectedScoreConceded;
        final double expectedEnemyEnergyGain;
        final double expectedEnemyEnergyDrain;
        final double energyDelta;
        final double expectedEnemyEnergyLoss;
        final double expectedOpponentCreditedDamage;

        ContinuationEventEffects(double expectedScoreGained,
                                 double expectedScoreConceded,
                                 double expectedEnemyEnergyGain,
                                 double expectedEnemyEnergyDrain,
                                 double energyDelta,
                                 double expectedEnemyEnergyLoss,
                                 double expectedOpponentCreditedDamage) {
            this.expectedScoreGained = expectedScoreGained;
            this.expectedScoreConceded = expectedScoreConceded;
            this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
            this.expectedEnemyEnergyDrain = expectedEnemyEnergyDrain;
            this.energyDelta = energyDelta;
            this.expectedEnemyEnergyLoss = expectedEnemyEnergyLoss;
            this.expectedOpponentCreditedDamage = expectedOpponentCreditedDamage;
        }
    }

    private enum ImmediateEventKind {
        OUR_SHOT,
        ENEMY_SHOT,
        WALL_HIT,
        RAM
    }

    private static final class ImmediateEvent {
        final double time;
        final int priority;
        final ImmediateEventKind kind;
        final double value;
        final double probability;
        final double shotPower;
        final Wave wave;
        final RamEvent ramEvent;
        final boolean deductEnergyCost;
        final boolean requireShooterAlive;

        ImmediateEvent(double time,
                       int priority,
                       ImmediateEventKind kind,
                       double value,
                       double probability,
                       double shotPower,
                       Wave wave,
                       RamEvent ramEvent,
                       boolean deductEnergyCost,
                       boolean requireShooterAlive) {
            this.time = time;
            this.priority = priority;
            this.kind = kind;
            this.value = value;
            this.probability = probability;
            this.shotPower = shotPower;
            this.wave = wave;
            this.ramEvent = ramEvent;
            this.deductEnergyCost = deductEnergyCost;
            this.requireShooterAlive = requireShooterAlive;
        }
    }

    private static final class BranchState {
        final double probability;
        double ourScore;
        double opponentScore;
        double ourEnergy;
        double enemyEnergy;
        double ourCreditedDamageOnEnemy;
        double enemyCreditedDamageOnUs;
        boolean ourKillBonusApplied;
        boolean enemyKillBonusApplied;
        boolean ourAlive;
        boolean enemyAlive;
        boolean roundResolved;

        BranchState(double probability,
                    double ourScore,
                    double opponentScore,
                    double ourEnergy,
                    double enemyEnergy,
                    double ourCreditedDamageOnEnemy,
                    double enemyCreditedDamageOnUs,
                    boolean ourKillBonusApplied,
                    boolean enemyKillBonusApplied,
                    boolean ourAlive,
                    boolean enemyAlive,
                    boolean roundResolved) {
            this.probability = probability;
            this.ourScore = ourScore;
            this.opponentScore = opponentScore;
            this.ourEnergy = ourEnergy;
            this.enemyEnergy = enemyEnergy;
            this.ourCreditedDamageOnEnemy = ourCreditedDamageOnEnemy;
            this.enemyCreditedDamageOnUs = enemyCreditedDamageOnUs;
            this.ourKillBonusApplied = ourKillBonusApplied;
            this.enemyKillBonusApplied = enemyKillBonusApplied;
            this.ourAlive = ourAlive;
            this.enemyAlive = enemyAlive;
            this.roundResolved = roundResolved;
        }

        BranchState copyWithProbability(double nextProbability) {
            return new BranchState(
                    nextProbability,
                    ourScore,
                    opponentScore,
                    ourEnergy,
                    enemyEnergy,
                    ourCreditedDamageOnEnemy,
                    enemyCreditedDamageOnUs,
                    ourKillBonusApplied,
                    enemyKillBonusApplied,
                    ourAlive,
                    enemyAlive,
                    roundResolved);
        }
    }
}
