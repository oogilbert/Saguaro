package oog.mega.saguaro.movement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;

final class MovementWavePlanner {
    static final class WaveSelection {
        final List<Wave> activeEnemyWaves;
        final EnemyInfo opponent;
        final List<Wave> scoringWaves;
        final List<Wave> planningWaves;

        WaveSelection(List<Wave> activeEnemyWaves,
                      EnemyInfo opponent,
                      List<Wave> scoringWaves,
                      List<Wave> planningWaves) {
            this.activeEnemyWaves = activeEnemyWaves;
            this.opponent = opponent;
            this.scoringWaves = scoringWaves;
            this.planningWaves = planningWaves;
        }
    }

    private final MovementEngine movement;
    private final Info info;

    MovementWavePlanner(MovementEngine movement, Info info) {
        if (movement == null || info == null) {
            throw new IllegalArgumentException("MovementWavePlanner requires non-null movement and info");
        }
        this.movement = movement;
        this.info = info;
    }

    WaveSelection selectWavesForState(long currentTime,
                                      double robotX,
                                      double robotY,
                                      double robotHeading,
                                      double robotVelocity) {
        List<Wave> activeEnemyWaves = collectActiveEnemyWaves(currentTime, robotX, robotY);
        EnemyInfo opponent = info.getEnemy();
        List<Wave> scoringWaves = buildScoringWavesForState(
                activeEnemyWaves,
                opponent,
                currentTime,
                robotX,
                robotY,
                robotHeading,
                robotVelocity,
                null);
        List<Wave> planningWaves = buildPlanningWavesForState(scoringWaves, robotX, robotY, currentTime);
        return new WaveSelection(activeEnemyWaves, opponent, scoringWaves, planningWaves);
    }

    List<Wave> getScoringWavesForState(long currentTime,
                                       double robotX,
                                       double robotY,
                                       double robotHeading,
                                       double robotVelocity) {
        return buildScoringWavesForState(
                collectActiveEnemyWaves(currentTime, robotX, robotY),
                info.getEnemy(),
                currentTime,
                robotX,
                robotY,
                robotHeading,
                robotVelocity,
                null);
    }

    List<Wave> getScoringWavesForState(long currentTime,
                                       double robotX,
                                       double robotY,
                                       double robotHeading,
                                       double robotVelocity,
                                       MovementEngine.MotionContext motionContext) {
        if (motionContext == null) {
            throw new IllegalArgumentException("Path-state scoring waves require motion context");
        }
        return buildScoringWavesForState(
                collectActiveEnemyWaves(currentTime, robotX, robotY),
                info.getEnemy(),
                currentTime,
                robotX,
                robotY,
                robotHeading,
                robotVelocity,
                motionContext);
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
                                double bfWidth,
                                double bfHeight,
                                List<Wave> referenceWaves) {
        Wave virtualWave = new Wave(
                originX,
                originY,
                Wave.bulletSpeed(firePower),
                fireTime,
                true,
                true);
        virtualWave.targetX = targetX;
        virtualWave.targetY = targetY;
        virtualWave.fireTimeContext = WaveContextFeatures.createWaveContext(
                originX,
                originY,
                virtualWave.speed,
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
                bfWidth,
                bfHeight,
                referenceWaves,
                null);
        virtualWave.fireTimeDistributionHandle =
                movement.distributionHandleForContextOrDefault(
                        virtualWave.fireTimeContext,
                        originX,
                        originY,
                        targetX,
                        targetY,
                        targetHeading,
                        targetVelocity,
                        virtualWave.speed);
        return virtualWave;
    }

    private static Wave createPendingVirtualEnemyWave(double originX,
                                                      double originY,
                                                      double firePower,
                                                      long fireTime) {
        return new Wave(originX, originY, Wave.bulletSpeed(firePower), fireTime, true, true);
    }

    static List<Wave> buildPlanningWavesForState(List<Wave> scoringWaves,
                                                 double x,
                                                 double y,
                                                 long currentTime) {
        List<Wave> planningWaves = new ArrayList<>(scoringWaves);
        // Keep overlapping waves in scoring so danger still accounts for their
        // remaining body overlap, but do not spend a planning segment on a wave
        // that has already touched the bot. Promoting the next wave to the
        // first planned wave preserves the full first-wave branching set.
        planningWaves.removeIf(w -> w.hasHit(x, y, currentTime));
        planningWaves.sort(Comparator.comparingInt(
                w -> PhysicsUtil.waveArrivalTick(w, x, y, currentTime)));
        if (planningWaves.size() > BotConfig.Movement.MAX_WAVE_DEPTH) {
            planningWaves.subList(BotConfig.Movement.MAX_WAVE_DEPTH, planningWaves.size()).clear();
        }
        return planningWaves;
    }

    static long estimateVirtualFireTime(long currentTime, double opponentGunHeat) {
        double effectiveGunHeat = Math.min(
                BotConfig.Movement.MAX_EFFECTIVE_VIRTUAL_GUN_HEAT, Math.max(0.0, opponentGunHeat));
        int ticksUntilGunReady = (int) Math.ceil(effectiveGunHeat / 0.1);
        // Real waves are observed one tick after firing via enemy energy drops.
        // Virtual waves model that pre-drop fire tick directly so origin and
        // reference angle both use the same simulated instant.
        return currentTime + Math.max(0, ticksUntilGunReady - 1);
    }

    private long virtualWaveFireTimeForState(long stateTime, EnemyInfo opponent) {
        if (opponent == null) {
            throw new IllegalArgumentException("Virtual-wave fire-time projection requires an opponent");
        }
        long liveTime = info.getRobot().getTime();
        long ticksAhead = Math.max(0L, stateTime - liveTime);
        double projectedGunHeat = Math.max(0.0, opponent.gunHeat - 0.1 * ticksAhead);
        return estimateVirtualFireTime(stateTime, projectedGunHeat);
    }

    private List<Wave> collectActiveEnemyWaves(long currentTime, double robotX, double robotY) {
        List<Wave> activeEnemyWaves = new ArrayList<>();
        for (Wave wave : info.getEnemyWaves()) {
            if (!wave.hasPassed(robotX, robotY, currentTime)) {
                activeEnemyWaves.add(wave);
            }
        }
        return activeEnemyWaves;
    }

    private List<Wave> buildScoringWavesForState(List<Wave> activeEnemyWaves,
                                                 EnemyInfo opponent,
                                                 long currentTime,
                                                 double robotX,
                                                 double robotY,
                                                 double robotHeading,
                                                 double robotVelocity,
                                                 MovementEngine.MotionContext motionContext) {
        List<Wave> scoringWaves = new ArrayList<>(activeEnemyWaves);
        scoringWaves.sort(Comparator.comparingInt(
                w -> PhysicsUtil.waveArrivalTick(w, robotX, robotY, currentTime)));
        if (scoringWaves.size() > BotConfig.Movement.MAX_WAVE_DEPTH) {
            scoringWaves.subList(BotConfig.Movement.MAX_WAVE_DEPTH, scoringWaves.size()).clear();
        }
        if (opponent == null || !opponent.alive || !opponent.seenThisRound) {
            return scoringWaves;
        }
        if (!BotConfig.Movement.ENABLE_VIRTUAL_WAVES
                || !info.getObservationProfile().shouldUseVirtualMovementWaves()
                || opponent.energy <= 0.0) {
            return scoringWaves;
        }

        // Keep movement planning/scoring wave sets aligned:
        // 0 real + 1 virtual, 1 real + 1 virtual, or 2 real waves.
        if (scoringWaves.size() >= BotConfig.Movement.MAX_WAVE_DEPTH) {
            return scoringWaves;
        }

        long fireTime = virtualWaveFireTimeForState(currentTime, opponent);
        EnemyInfo.PredictedPosition opponentAtFireTime = opponent.predictPositionAtTime(
                fireTime, info.getBattlefieldWidth(), info.getBattlefieldHeight());
        Wave virtualWave;
        if (motionContext == null) {
            virtualWave = createPendingVirtualEnemyWave(
                    opponentAtFireTime.x,
                    opponentAtFireTime.y,
                    3.0,
                    fireTime);
        } else {
            virtualWave = createVirtualEnemyWave(
                    opponentAtFireTime.x,
                    opponentAtFireTime.y,
                    3.0,
                    fireTime,
                    robotX,
                    robotY,
                    robotHeading,
                    robotVelocity,
                    motionContext.headingDelta,
                    motionContext.velocityDelta,
                    motionContext.accelerationSign,
                    motionContext.ticksSinceVelocityReversal,
                    motionContext.ticksSinceDecel,
                    motionContext.distanceLast10,
                    motionContext.distanceLast20,
                    info.didLastEnemyWaveHitRobot(),
                    WaveContextFeatures.approximateLateralDirectionSign(
                            opponentAtFireTime.x,
                            opponentAtFireTime.y,
                            robotX,
                            robotY,
                            robotHeading,
                            motionContext.lastNonZeroVelocitySign),
                    info.getBattlefieldWidth(),
                    info.getBattlefieldHeight(),
                    scoringWaves);
        }
        scoringWaves.add(virtualWave);
        return scoringWaves;
    }
}
