package oog.mega.saguaro.movement;

import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.PhysicsUtil;

public final class CandidatePath {
    public final PhysicsUtil.Trajectory trajectory;
    public final long startTime;
    public final List<Wave> scoringWaves;
    public final double totalDanger;
    public final double expectedEnemyEnergyGain;
    public final double wallHitDamage;
    public final double ramScoreDealt;
    public final double ramScoreTaken;
    public final double ramEnergyLoss;
    public final double ramEnemyEnergyLoss;
    public final List<RamEvent> ramEvents;
    public final double firstTargetX;
    public final double firstTargetY;
    public final double firstWaveTargetAngle;
    public final double firstWaveSafeSpotX;
    public final double firstWaveSafeSpotY;
    public final Wave firstWave;
    public final int firstWaveSafeSpotTick;
    public final int firstLegDurationTicks;
    public final List<PathWaveIntersection> pathIntersections;
    public final List<PathLeg> segmentLegs;
    public final long familyId;

    public CandidatePath(PhysicsUtil.Trajectory trajectory,
                         long startTime,
                         List<Wave> scoringWaves,
                         double totalDanger,
                         double expectedEnemyEnergyGain,
                         double wallHitDamage,
                         double ramScoreDealt,
                         double ramScoreTaken,
                         double ramEnergyLoss,
                         double ramEnemyEnergyLoss,
                         List<RamEvent> ramEvents,
                         double firstTargetX,
                         double firstTargetY,
                         double firstWaveTargetAngle,
                         double firstWaveSafeSpotX,
                         double firstWaveSafeSpotY,
                         Wave firstWave,
                         int firstWaveSafeSpotTick,
                         int firstLegDurationTicks,
                         List<PathWaveIntersection> pathIntersections,
                         List<PathLeg> segmentLegs,
                         long familyId) {
        this.trajectory = trajectory;
        this.startTime = startTime;
        this.scoringWaves = scoringWaves == null ? Collections.<Wave>emptyList() : scoringWaves;
        this.totalDanger = totalDanger;
        this.expectedEnemyEnergyGain = expectedEnemyEnergyGain;
        this.wallHitDamage = wallHitDamage;
        this.ramScoreDealt = ramScoreDealt;
        this.ramScoreTaken = ramScoreTaken;
        this.ramEnergyLoss = ramEnergyLoss;
        this.ramEnemyEnergyLoss = ramEnemyEnergyLoss;
        this.ramEvents = ramEvents == null ? Collections.<RamEvent>emptyList() : ramEvents;
        this.firstTargetX = firstTargetX;
        this.firstTargetY = firstTargetY;
        this.firstWaveTargetAngle = firstWaveTargetAngle;
        this.firstWaveSafeSpotX = firstWaveSafeSpotX;
        this.firstWaveSafeSpotY = firstWaveSafeSpotY;
        this.firstWave = firstWave;
        this.firstWaveSafeSpotTick = firstWaveSafeSpotTick;
        this.firstLegDurationTicks = firstLegDurationTicks;
        this.pathIntersections = pathIntersections;
        this.segmentLegs = segmentLegs == null ? Collections.<PathLeg>emptyList() : segmentLegs;
        this.familyId = familyId;
    }
}
