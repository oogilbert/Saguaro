package oog.mega.saguaro.movement;

import java.util.List;

import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.mode.scripted.OpponentDriveSimulator;

public interface MovementController {
    GuessFactorDistribution getDistributionForContext(WaveContextFeatures.WaveContext context);

    GuessFactorDistribution queryDistributionForEnemyWaveAtFireTime(Wave wave);

    PathGenerationContext createPathGenerationContext();

    List<CandidatePath> generateCandidatePaths(PathGenerationContext context);

    List<CandidatePath> generateCandidatePaths(PathGenerationContext context,
                                               List<CandidatePath> carriedForwardFamilies);

    List<PathWaveIntersection> collectPathWaveIntersections(
            CandidatePath path,
            PathIntersectionContext context);

    List<Wave> getScoringWavesForState(long currentTime,
                                       double robotX,
                                       double robotY,
                                       double robotHeading,
                                       double robotVelocity);

    List<Wave> getScoringWavesForPathState(CandidatePath path, int tickOffset);

    List<PathLeg> generateBestRandomTail(PhysicsUtil.Trajectory committedPrefix,
                                         long prefixStartTime,
                                         PredictedOpponentState opponentStart,
                                         OpponentDriveSimulator.Instruction opponentInstruction,
                                         int minTailTicks,
                                         List<PathLeg> carryForwardTail);

    default String describeLatestPathPlanningDiagnostics() {
        return "planning=n/a";
    }
}
