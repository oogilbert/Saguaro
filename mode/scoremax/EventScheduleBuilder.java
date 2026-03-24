package oog.mega.saguaro.mode.scoremax;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import oog.mega.saguaro.movement.RamEvent;

final class EventScheduleBuilder {
    static final double EVENT_TIME_EPSILON = 1e-9;

    private EventScheduleBuilder() {
    }

    enum ImmediateEventKind {
        OUR_SHOT,
        ENEMY_SHOT,
        WALL_HIT,
        RAM
    }

    static final class ImmediateEvent {
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

    static final class BranchedEventSchedule {
        final List<ImmediateEvent> events;
        final List<TailShotCredit> tailShots;

        BranchedEventSchedule(List<ImmediateEvent> events,
                              List<TailShotCredit> tailShots) {
            this.events = events;
            this.tailShots = tailShots;
        }
    }

    static final class TailShotCredit {
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

    static final class EnemyActualShot {
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

    static final class EnemyContinuationPlan {
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

    static BranchedEventSchedule buildBranchedEventSchedule(CandidatePath path,
                                                             List<PathWaveIntersection> pathIntersections,
                                                             ShotPlanner.ShotSelection selection) {
        List<ImmediateEvent> shotEvents = new ArrayList<>();
        List<TailShotCredit> tailShots = new ArrayList<>();
        double horizonTime = boundedContinuationHorizonTime(path);
        addOurShotEvents(shotEvents, tailShots, selection, horizonTime);
        EnemyContinuationPlan enemyPlan = buildEnemyContinuationPlan(pathIntersections, selection);
        addEnemyShotEvents(shotEvents, tailShots, enemyPlan, horizonTime);
        trimBranchedShotEvents(shotEvents, tailShots);

        List<ImmediateEvent> events = new ArrayList<>(shotEvents);
        addWallHitEvents(events, path);
        addRamEvents(events, path);
        sortImmediateEvents(events);
        return new BranchedEventSchedule(events, tailShots);
    }

    static void addOurShotEvents(List<ImmediateEvent> events,
                                  List<TailShotCredit> tailShots,
                                  ShotPlanner.ShotSelection selection,
                                  double horizonTime) {
        if (selection == null || !(selection.power >= ShotPlanner.MIN_FIRE_POWER)) {
            return;
        }
        int explicitShotCount = 0;
        events.add(new ImmediateEvent(
                selection.fireTime,
                0,
                ImmediateEventKind.OUR_SHOT,
                0.0,
                selection.hitProbability,
                selection.power,
                null,
                null,
                true,
                true));
        explicitShotCount++;
        if (!Double.isFinite(horizonTime)) {
            return;
        }
        double cadenceTicks = continuationCadenceTicks(selection.power);
        if (!(cadenceTicks > 0.0)) {
            return;
        }
        double windowStartTime = selection.fireTime;
        double nextShotTime = windowStartTime + cadenceTicks;
        while (nextShotTime <= horizonTime + EVENT_TIME_EPSILON
                && explicitShotCount < BotConfig.ScoreMax.MAX_BRANCHED_SHOT_EVENTS) {
            events.add(new ImmediateEvent(
                    nextShotTime,
                    0,
                    ImmediateEventKind.OUR_SHOT,
                    0.0,
                    selection.hitProbability,
                    selection.power,
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
                selection.power,
                selection.hitProbability,
                true,
                true,
                tailShotWeight(windowStartTime, cadenceTicks, cadenceTicks, horizonTime));
    }

    static void addWallHitEvents(List<ImmediateEvent> events,
                                  CandidatePath path) {
        if (path == null || path.trajectory == null) {
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

    static void addRamEvents(List<ImmediateEvent> events,
                              CandidatePath path) {
        if (path == null || path.ramEvents == null) {
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
                    0.0,
                    1.0,
                    0.0,
                    null,
                    ramEvent,
                    false,
                    false));
        }
    }

    static EnemyContinuationPlan buildEnemyContinuationPlan(List<PathWaveIntersection> pathIntersections,
                                                             ShotPlanner.ShotSelection shotSelection) {
        if (pathIntersections == null || pathIntersections.isEmpty()) {
            return null;
        }
        List<EnemyActualShot> actualShots = new ArrayList<>();
        for (PathWaveIntersection intersection : pathIntersections) {
            if (intersection == null || intersection.wave == null || !intersection.wave.isEnemy) {
                continue;
            }
            actualShots.add(new EnemyActualShot(
                    intersection.firstContactTime,
                    intersection.wave.fireTime,
                    intersection.wave.getFirepower(),
                    waveHitProbability(shotSelection, intersection),
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
            return Long.compare(a.wave.fireTime, b.wave.fireTime);
        });
        EnemyActualShot firstShot = actualShots.get(0);
        double templateCadenceTicks = continuationCadenceTicks(firstShot.shotPower);
        double continuationWindowStartTime = firstShot.eventTime;
        double firstContinuationCadenceTicks = templateCadenceTicks;
        if (actualShots.size() >= 2) {
            EnemyActualShot secondShot = actualShots.get(1);
            double observedCadenceTicks = secondShot.wave.fireTime > firstShot.wave.fireTime
                    ? secondShot.wave.fireTime - firstShot.wave.fireTime
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

    static void addEnemyShotEvents(List<ImmediateEvent> events,
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

    static void trimBranchedShotEvents(List<ImmediateEvent> shotEvents,
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

    static void sortImmediateEvents(List<ImmediateEvent> events) {
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

    static double waveHitProbability(ShotPlanner.ShotSelection shotSelection,
                                     PathWaveIntersection intersection) {
        if (intersection == null || intersection.wave == null) {
            return 0.0;
        }
        if (shotSelection != null && shotSelection.enemyWaveHitProbabilities != null) {
            Double hitProbability = shotSelection.enemyWaveHitProbabilities.get(intersection.wave);
            if (hitProbability != null) {
                return Math.max(0.0, Math.min(1.0, hitProbability.doubleValue()));
            }
        }
        if (intersection.distribution == null
                || intersection.exposedGfIntervals == null
                || intersection.exposedGfIntervals.isEmpty()) {
            return 0.0;
        }
        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        BulletShadowUtil.integrateWeightedIntervals(
                                intersection.distribution,
                                intersection.exposedGfIntervals,
                                -1.0,
                                1.0)));
    }

    static double lastEnemyWavePassTime(CandidatePath path) {
        if (path == null || path.scoringWaves == null || path.scoringWaves.isEmpty()) {
            return Double.NaN;
        }
        double lastPassTime = Double.NEGATIVE_INFINITY;
        for (Wave wave : path.scoringWaves) {
            if (wave == null || !wave.isEnemy) {
                continue;
            }
            double passTime = explicitWavePassTime(path, wave);
            if (Double.isFinite(passTime) && passTime > lastPassTime) {
                lastPassTime = passTime;
            }
        }
        return lastPassTime;
    }

    static double explicitWavePassTime(CandidatePath path, Wave wave) {
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

    static void appendTailShotCredit(List<TailShotCredit> tailShots,
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

    static EnemyActualShot latestActualShotByFireTime(List<EnemyActualShot> actualShots) {
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

    static double tailShotWeight(double windowStartTime,
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

    static double overlapFraction(double windowStartTime,
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

    static double boundedContinuationHorizonTime(CandidatePath path) {
        return lastEnemyWavePassTime(path);
    }

    static double continuationCadenceTicks(double power) {
        return Math.max(1.0, (1.0 + power / 5.0) / 0.1);
    }
}
