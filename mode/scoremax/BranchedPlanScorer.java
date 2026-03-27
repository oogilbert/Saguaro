package oog.mega.saguaro.mode.scoremax;

import java.util.ArrayList;
import java.util.List;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.learning.ScoreMaxScoreHistoryProfile;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.state.RobocodeScoreUtil;
import oog.mega.saguaro.info.state.RobotSnapshot;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import oog.mega.saguaro.movement.RamEvent;
import robocode.Rules;

final class BranchedPlanScorer {
    static final double MIN_CONTINUATION_POWER = 0.1;
    static final double MIN_TERMINAL_RATE = 1e-6;

    private Info info;
    private ScoreContext scoreContext;
    private final RobocodeScoreUtil.HitScoreScratch hitScoreScratch = new RobocodeScoreUtil.HitScoreScratch();
    private int scoreMaxSelectedRoundCount;
    private int lastCountedScoreMaxRound;

    void init(Info info) {
        this.info = info;
        if (info.getRobot().getRoundNum() == 0) {
            this.scoreMaxSelectedRoundCount = 0;
            this.lastCountedScoreMaxRound = Integer.MIN_VALUE;
        }
        this.scoreContext = null;
    }

    static final class PlanOutcomeBranch {
        double probability;
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

        PlanOutcomeBranch(double probability,
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

        PlanOutcomeBranch copyWithProbability(double probability) {
            return new PlanOutcomeBranch(
                    probability,
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

    static final class BranchValue {
        final double value;
        final double absoluteScore;

        BranchValue(double value, double absoluteScore) {
            this.value = value;
            this.absoluteScore = absoluteScore;
        }
    }

    static final class BranchAggregation {
        double weightedValue;
        double weightedAbsoluteScore;
        double settledProbability;
    }

    static final class ContinuationDeltas {
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

    static final class ContinuationEventEffects {
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

    static class ScoreContext {
        final double ourScore;
        final double opponentScore;
        final boolean bonusEnabled;
        final double currentEnemyEnergy;
        final double currentWinProbability;
        final double currentOurTerminalAdd;
        final double currentOpponentTerminalAdd;

        ScoreContext(double ourScore,
                     double opponentScore,
                     boolean bonusEnabled,
                     double currentEnemyEnergy,
                     double currentWinProbability,
                     double currentOurTerminalAdd,
                     double currentOpponentTerminalAdd) {
            this.ourScore = ourScore;
            this.opponentScore = opponentScore;
            this.bonusEnabled = bonusEnabled;
            this.currentEnemyEnergy = currentEnemyEnergy;
            this.currentWinProbability = currentWinProbability;
            this.currentOurTerminalAdd = currentOurTerminalAdd;
            this.currentOpponentTerminalAdd = currentOpponentTerminalAdd;
        }
    }

    static final class TerminalScoreEstimate {
        final double ourTerminalAdd;
        final double opponentTerminalAdd;

        TerminalScoreEstimate(double ourTerminalAdd, double opponentTerminalAdd) {
            this.ourTerminalAdd = ourTerminalAdd;
            this.opponentTerminalAdd = opponentTerminalAdd;
        }
    }

    ShotPlanner.ShotScore scoreShotSelection(CandidatePath path,
                                              List<PathWaveIntersection> pathIntersections,
                                              ShotPlanner.ShotSelection selection,
                                              double currentOurEnergy) {
        return evaluateBranchedPlanScore(path, pathIntersections, selection, currentOurEnergy);
    }

    ShotPlanner.ShotScore evaluateBranchedPlanScore(CandidatePath path,
                                                     List<PathWaveIntersection> pathIntersections,
                                                     ShotPlanner.ShotSelection selection,
                                                     double currentOurEnergy) {
        if (path == null || selection == null) {
            return new ShotPlanner.ShotScore(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        }
        double currentEnemyEnergy = currentEnemyEnergyForTerminalScoring(scoreContext);
        PlanOutcomeBranch initialBranch = new PlanOutcomeBranch(
                1.0,
                scoreContext != null ? scoreContext.ourScore : info.getOurScore(),
                scoreContext != null ? scoreContext.opponentScore : info.getOpponentScore(),
                Math.max(0.0, currentOurEnergy),
                Math.max(0.0, currentEnemyEnergy),
                Math.max(0.0, info.getOurCreditedDamageOnEnemyThisRound()),
                Math.max(0.0, info.getEnemyCreditedDamageOnUsThisRound()),
                false,
                false,
                currentOurEnergy > 0.0,
                currentEnemyEnergy > 0.0,
                false);
        EventScheduleBuilder.BranchedEventSchedule schedule = EventScheduleBuilder.buildBranchedEventSchedule(path, pathIntersections, selection);
        List<PlanOutcomeBranch> branches = new ArrayList<>();
        List<PlanOutcomeBranch> nextBranches = new ArrayList<>();
        List<PlanOutcomeBranch> activeBranches = new ArrayList<>();
        branches.add(initialBranch);
        BranchAggregation aggregation = new BranchAggregation();
        double currentEventTime = Double.NaN;

        for (EventScheduleBuilder.ImmediateEvent event : schedule.events) {
            if (Double.isFinite(currentEventTime) && !sameEventTime(event.time, currentEventTime)) {
                activeBranches.clear();
                settleResolvedBranches(branches, activeBranches, aggregation);
                branches.clear();
                branches.addAll(activeBranches);
                if (branches.isEmpty()) {
                    if (aggregation.settledProbability > 0.0) {
                        break;
                    }
                    return new ShotPlanner.ShotScore(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
                }
            }
            currentEventTime = event.time;
            nextBranches.clear();
            for (PlanOutcomeBranch branch : branches) {
                applyImmediateEvent(nextBranches, branch, event);
            }
            branches.clear();
            for (PlanOutcomeBranch branch : nextBranches) {
                if (branch == null || !(branch.probability > 0.0)) {
                    continue;
                }
                branches.add(branch);
            }
            if (branches.isEmpty()) {
                if (aggregation.settledProbability > 0.0) {
                    break;
                }
                return new ShotPlanner.ShotScore(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
            }
        }

        activeBranches.clear();
        settleResolvedBranches(branches, activeBranches, aggregation);
        branches.clear();
        branches.addAll(activeBranches);

        for (PlanOutcomeBranch branch : branches) {
            BranchValue branchValue = evaluateBranchValue(selection, schedule, branch);
            aggregation.weightedValue += branch.probability * branchValue.value;
            aggregation.weightedAbsoluteScore += branch.probability * branchValue.absoluteScore;
        }
        return new ShotPlanner.ShotScore(
                aggregation.weightedValue - currentPlanValue(currentOurEnergy),
                aggregation.weightedAbsoluteScore);
    }

    private void applyImmediateEvent(List<PlanOutcomeBranch> nextBranches,
                                     PlanOutcomeBranch branch,
                                     EventScheduleBuilder.ImmediateEvent event) {
        if (branch == null || event == null || !(branch.probability > 0.0)) {
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
                throw new IllegalStateException("Unhandled immediate event kind: " + event.kind);
        }
    }

    private void applyWallHitEvent(PlanOutcomeBranch branch,
                                   double wallDamage) {
        if (branch == null || !(wallDamage > 0.0) || !branch.ourAlive) {
            return;
        }
        branch.ourEnergy = Math.max(0.0, branch.ourEnergy - wallDamage);
        updateAliveStates(branch);
    }

    private void applyRamEvent(PlanOutcomeBranch branch,
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

    private void branchShotEvent(List<PlanOutcomeBranch> nextBranches,
                                 PlanOutcomeBranch branch,
                                 EventScheduleBuilder.ImmediateEvent event) {
        if (nextBranches == null || branch == null || event == null || !canResolveShotEvent(branch, event)) {
            if (nextBranches != null && branch != null && event != null) {
                nextBranches.add(branch);
            }
            return;
        }
        double clampedHitProbability = Math.max(0.0, Math.min(1.0, event.probability));
        double missProbability = 1.0 - clampedHitProbability;
        if (missProbability > 0.0) {
            PlanOutcomeBranch missBranch = branch.copyWithProbability(branch.probability * missProbability);
            applyShotOutcome(missBranch, event, false);
            nextBranches.add(missBranch);
        }
        if (!(clampedHitProbability > 0.0)) {
            return;
        }
        PlanOutcomeBranch hitBranch = branch.copyWithProbability(branch.probability * clampedHitProbability);
        applyShotOutcome(hitBranch, event, true);
        nextBranches.add(hitBranch);
    }

    private boolean canResolveShotEvent(PlanOutcomeBranch branch,
                                        EventScheduleBuilder.ImmediateEvent event) {
        if (branch == null || event == null || !(event.shotPower >= ShotPlanner.MIN_FIRE_POWER)) {
            return false;
        }
        if (event.kind == EventScheduleBuilder.ImmediateEventKind.OUR_SHOT) {
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

    private void applyShotOutcome(PlanOutcomeBranch branch,
                                  EventScheduleBuilder.ImmediateEvent event,
                                  boolean hit) {
        if (branch == null || event == null) {
            return;
        }
        boolean ourSide = event.kind == EventScheduleBuilder.ImmediateEventKind.OUR_SHOT;
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

    private void updateAliveStates(PlanOutcomeBranch branch) {
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

    static boolean sameEventTime(double a,
                                  double b) {
        return Math.abs(a - b) <= EventScheduleBuilder.EVENT_TIME_EPSILON;
    }

    private void settleResolvedBranches(List<PlanOutcomeBranch> source,
                                        List<PlanOutcomeBranch> unresolvedOut,
                                        BranchAggregation aggregation) {
        if (source == null || unresolvedOut == null || aggregation == null) {
            return;
        }
        for (PlanOutcomeBranch branch : source) {
            if (branch == null || !(branch.probability > 0.0)) {
                continue;
            }
            if (isSettledResolvedBranch(branch)) {
                BranchValue branchValue = resolvedBranchValue(branch);
                aggregation.weightedValue += branch.probability * branchValue.value;
                aggregation.weightedAbsoluteScore += branch.probability * branchValue.absoluteScore;
                aggregation.settledProbability += branch.probability;
                continue;
            }
            unresolvedOut.add(branch);
        }
    }

    private BranchValue evaluateBranchValue(ShotPlanner.ShotSelection selection,
                                            EventScheduleBuilder.BranchedEventSchedule schedule,
                                            PlanOutcomeBranch branch) {
        if (branch.roundResolved) {
            return resolvedBranchValue(branch);
        }
        ContinuationDeltas continuationDeltas = estimateBoundedContinuationDeltas(
                schedule,
                branch);
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
        double continuationFirePower = selection != null ? selection.continuationFirePower : 0.0;
        return new BranchValue(
                evaluateContinuationBranchValue(
                        projectedOurScore,
                        projectedOpponentScore,
                        projectedOurEnergy,
                        projectedEnemyEnergy,
                        0,
                        0,
                        projectedOurCreditedDamage,
                        projectedEnemyCreditedDamage,
                        continuationFirePower),
                projectedOurScore - projectedOpponentScore);
    }

    static boolean isSettledResolvedBranch(PlanOutcomeBranch branch) {
        return branch != null && branch.roundResolved;
    }

    static BranchValue resolvedBranchValue(PlanOutcomeBranch branch) {
        return new BranchValue(
                exactShareValue(branch.ourScore, branch.opponentScore),
                branch.ourScore - branch.opponentScore);
    }

    private ContinuationDeltas estimateBoundedContinuationDeltas(EventScheduleBuilder.BranchedEventSchedule schedule,
                                                                  PlanOutcomeBranch branch) {
        ContinuationDeltas deltas = new ContinuationDeltas();
        if (schedule == null || branch == null || !branch.ourAlive || !branch.enemyAlive || branch.roundResolved) {
            return deltas;
        }
        for (EventScheduleBuilder.TailShotCredit tailShot : schedule.tailShots) {
            addTailShotCredit(deltas, tailShot, branch);
        }
        return deltas;
    }

    private void addTailShotCredit(ContinuationDeltas deltas,
                                   EventScheduleBuilder.TailShotCredit tailShot,
                                   PlanOutcomeBranch branch) {
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

    private ContinuationEventEffects buildOurContinuationEventEffects(EventScheduleBuilder.TailShotCredit tailShot,
                                                                      double ourEnergy,
                                                                      double enemyEnergy) {
        if (tailShot == null
                || !(tailShot.shotPower >= MIN_CONTINUATION_POWER)
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

    private ContinuationEventEffects buildEnemyContinuationEventEffects(EventScheduleBuilder.TailShotCredit tailShot,
                                                                        double ourEnergy,
                                                                        double enemyEnergy) {
        if (tailShot == null
                || !(tailShot.shotPower >= MIN_CONTINUATION_POWER)
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

    double currentPlanValue(double currentOurEnergy) {
        ScoreContext context = scoreContext;
        if (context != null) {
            double denominator = context.ourScore
                    + context.opponentScore
                    + context.currentOurTerminalAdd
                    + context.currentOpponentTerminalAdd;
            if (denominator > 0.0) {
                return (context.ourScore + context.currentOurTerminalAdd) / denominator;
            }
            return context.ourScore - context.opponentScore;
        }
        return evaluateContinuationBranchValue(
                info.getOurScore(),
                info.getOpponentScore(),
                currentOurEnergy,
                currentEnemyEnergyForScoring(),
                0,
                0,
                info.getOurCreditedDamageOnEnemyThisRound(),
                info.getEnemyCreditedDamageOnUsThisRound(),
                0.0);
    }

    static double exactShareValue(double ourScore,
                                   double opponentScore) {
        double denominator = ourScore + opponentScore;
        if (denominator > 0.0) {
            return ourScore / denominator;
        }
        return ourScore - opponentScore;
    }

    void prepareScoreContext(RobotSnapshot robotState, double baselineContinuationFirePower) {
        double ourScore = ScoreMaxScoreHistoryProfile.INSTANCE.getCurrentBattleOurScore();
        double opponentScore = ScoreMaxScoreHistoryProfile.INSTANCE.getCurrentBattleOpponentScore();
        boolean bonusEnabled = isEndOfRoundBonusEvEnabledForState();
        if (!bonusEnabled) {
            scoreContext = new ScoreContext(
                    ourScore, opponentScore, false, 0.0, 0.0, 0.0, 0.0);
            return;
        }

        EnemyInfo enemy = info.getEnemy();
        double currentEnemyEnergy = Math.max(0.0, enemy.energy);
        double currentWinProbability = WinProbabilityModel.estimateWinProbability(
                robotState.energy,
                currentEnemyEnergy,
                getAssumedWinProbabilityParams());
        TerminalScoreEstimate currentTerminalEstimate = estimateTerminalScoreAdds(
                info.getOurCreditedDamageOnEnemyThisRound(),
                info.getEnemyCreditedDamageOnUsThisRound(),
                robotState.energy,
                currentEnemyEnergy,
                currentWinProbability,
                baselineContinuationFirePower);
        scoreContext = new ScoreContext(
                ourScore,
                opponentScore,
                true,
                currentEnemyEnergy,
                currentWinProbability,
                currentTerminalEstimate.ourTerminalAdd,
                currentTerminalEstimate.opponentTerminalAdd);
    }

    void clearScoreContext() {
        this.scoreContext = null;
    }

    private double evaluateContinuationBranchValue(double ourScore,
                                                    double opponentScore,
                                                    double ourEnergy,
                                                    double enemyEnergy,
                                                    int ourReadyTicks,
                                                    int enemyReadyTicks,
                                                    double ourCreditedDamageOnEnemy,
                                                    double enemyCreditedDamageOnUs,
                                                    double continuationFirePower) {
        boolean bonusEnabled = isEndOfRoundBonusEvEnabledForState();
        int clampedReadyTickLead = Math.max(
                -BotConfig.ScoreMax.MAX_READY_TICK_LEAD,
                Math.min(BotConfig.ScoreMax.MAX_READY_TICK_LEAD, enemyReadyTicks - ourReadyTicks));
        double readinessEnergyEquivalent =
                clampedReadyTickLead * BotConfig.ScoreMax.DEFAULT_READY_TICK_ENERGY_EQUIVALENT;
        double effectiveOurEnergy = Math.max(0.0, ourEnergy + 0.5 * readinessEnergyEquivalent);
        double effectiveEnemyEnergy = Math.max(0.0, enemyEnergy - 0.5 * readinessEnergyEquivalent);
        if (!bonusEnabled) {
            double denominator = ourScore + opponentScore;
            return denominator > 0.0
                    ? ourScore / denominator
                    : ourScore - opponentScore + 0.1 * (effectiveOurEnergy - effectiveEnemyEnergy);
        }
        double winProbability = WinProbabilityModel.estimateWinProbability(
                effectiveOurEnergy,
                effectiveEnemyEnergy,
                getAssumedWinProbabilityParams());
        TerminalScoreEstimate terminalEstimate = estimateTerminalScoreAdds(
                ourCreditedDamageOnEnemy,
                enemyCreditedDamageOnUs,
                ourEnergy,
                enemyEnergy,
                winProbability,
                continuationFirePower);
        double denominator = ourScore + opponentScore
                + terminalEstimate.ourTerminalAdd
                + terminalEstimate.opponentTerminalAdd;
        if (denominator <= 0.0) {
            return winProbability;
        }
        return (ourScore + terminalEstimate.ourTerminalAdd) / denominator;
    }

    boolean isEndOfRoundBonusEvEnabledForState() {
        if (!BotConfig.ScoreMax.ENABLE_END_OF_ROUND_BONUS_EV) {
            return false;
        }
        EnemyInfo enemy = info.getEnemy();
        return enemy != null && enemy.alive && enemy.seenThisRound;
    }

    private TerminalScoreEstimate estimateTerminalScoreAdds(double ourCreditedDamageOnEnemy,
                                                            double enemyCreditedDamageOnUs,
                                                            double ourEnergy,
                                                            double enemyEnergy,
                                                            double ourWinProbability,
                                                            double continuationFirePower) {
        if (!isEndOfRoundBonusEvEnabledForState()) {
            return new TerminalScoreEstimate(0.0, 0.0);
        }

        double clampedOurWinProbability = Math.max(0.0, Math.min(1.0, ourWinProbability));
        double clampedEnemyWinProbability = 1.0 - clampedOurWinProbability;
        double ourPower = sanitizeContinuationPower(continuationFirePower, ourEnergy);
        double enemyPower = sanitizeContinuationPower(assumedEnemyContinuationPower(enemyEnergy), enemyEnergy);

        double ourHitRate = ourPower >= MIN_CONTINUATION_POWER
                ? estimateOurContinuationHitRate(ourPower) * ShotPlanner.targetingDataHitRateScale()
                : 0.0;
        double enemyHitRate = enemyPower >= MIN_CONTINUATION_POWER
                ? estimateEnemyContinuationHitRate(enemyPower)
                : 0.0;

        double ourDamageRate = continuationBulletDamageRate(ourPower, ourHitRate);
        double enemyDamageRate = continuationBulletDamageRate(enemyPower, enemyHitRate);
        double ourNetSelfEnergyLossRate = continuationNetSelfEnergyLossRate(ourPower, ourHitRate);
        double enemyNetSelfEnergyLossRate = continuationNetSelfEnergyLossRate(enemyPower, enemyHitRate);
        double enemyLossRate = ourDamageRate + enemyNetSelfEnergyLossRate;
        double ourLossRate = enemyDamageRate + ourNetSelfEnergyLossRate;

        // Advance both robots under the same continuation model and stop at the
        // first zero-energy event; after that point the coupled tail is invalid.
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

    static double continuationBulletDamageRate(double power, double hitRate) {
        if (!(power >= MIN_CONTINUATION_POWER)) {
            return 0.0;
        }
        return hitRate * robocode.Rules.getBulletDamage(power) / EventScheduleBuilder.continuationCadenceTicks(power);
    }

    static double continuationNetSelfEnergyLossRate(double power, double hitRate) {
        if (!(power >= MIN_CONTINUATION_POWER)) {
            return 0.0;
        }
        return (power - 3.0 * hitRate * power) / EventScheduleBuilder.continuationCadenceTicks(power);
    }

    static double terminalFightDuration(double ourEnergy,
                                         double ourLossRate,
                                         double enemyEnergy,
                                         double enemyLossRate) {
        double ourTimeToZero = timeToZeroEnergy(ourEnergy, ourLossRate);
        double enemyTimeToZero = timeToZeroEnergy(enemyEnergy, enemyLossRate);
        return Math.min(ourTimeToZero, enemyTimeToZero);
    }

    static double timeToZeroEnergy(double energy, double lossRate) {
        if (!(energy > 0.0)) {
            return 0.0;
        }
        if (!(lossRate > MIN_TERMINAL_RATE)) {
            return Double.POSITIVE_INFINITY;
        }
        return energy / lossRate;
    }

    static double expectedCreditedDamageOverDuration(double damageRate,
                                                      double duration) {
        if (!(damageRate > 0.0) || !(duration > 0.0) || !Double.isFinite(duration)) {
            return 0.0;
        }
        return damageRate * duration;
    }

    double currentEnemyDistance() {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.seenThisRound) {
            return 400.0;
        }
        return Math.max(1.0, enemy.distance);
    }

    double currentEnemyEnergyForTerminalScoring(ScoreContext context) {
        if (context != null) {
            return context.currentEnemyEnergy;
        }
        EnemyInfo enemy = info.getEnemy();
        return enemy != null ? Math.max(0.0, enemy.energy) : 0.0;
    }

    static int nominalFlightTicksForPower(double distance, double power) {
        if (!(power >= MIN_CONTINUATION_POWER)) {
            return 65;
        }
        return Wave.nominalFlightTicks(distance, Wave.bulletSpeed(power));
    }

    private double estimateOurContinuationHitRate(double power) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.seenThisRound) {
            return info.getBulletPowerHitRateTracker().estimateHitRate(
                    nominalFlightTicksForPower(400.0, power));
        }
        return info.getBulletPowerHitRateTracker().estimateHitRate(
                info.getRobot().getX(),
                info.getRobot().getY(),
                enemy.x,
                enemy.y,
                Wave.bulletSpeed(power),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    private double estimateEnemyContinuationHitRate(double power) {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.seenThisRound) {
            return info.getEnemyBulletHitRateTracker().estimateHitRate(
                    nominalFlightTicksForPower(400.0, power));
        }
        return info.getEnemyBulletHitRateTracker().estimateHitRate(
                enemy.x,
                enemy.y,
                info.getRobot().getX(),
                info.getRobot().getY(),
                Wave.bulletSpeed(power),
                info.getBattlefieldWidth(),
                info.getBattlefieldHeight());
    }

    static double sanitizeContinuationPower(double power, double availableEnergy) {
        if (!(power >= MIN_CONTINUATION_POWER) || !(availableEnergy >= MIN_CONTINUATION_POWER)) {
            return 0.0;
        }
        return Math.min(power, Math.min(ShotPlanner.MAX_FIRE_POWER, availableEnergy));
    }

    double assumedEnemyContinuationPower(double enemyEnergy) {
        EnemyInfo enemy = info.getEnemy();
        double lastDetectedBulletPower = enemy != null ? enemy.lastDetectedBulletPower : Double.NaN;
        double assumedPower = Double.isFinite(lastDetectedBulletPower)
                ? lastDetectedBulletPower
                : BotConfig.ScoreMax.DEFAULT_ENEMY_CONTINUATION_POWER;
        return Math.min(assumedPower, Math.max(0.0, enemyEnergy));
    }

    WinProbabilityModel.Params getAssumedWinProbabilityParams() {
        double survivalPrior = adjustedSurvivalPrior(info.getRoundOutcomeProfile().getSurvivalPrior());
        double clamped = Math.max(
                BotConfig.ScoreMax.MIN_PRIOR_WIN_PROBABILITY,
                Math.min(BotConfig.ScoreMax.MAX_PRIOR_WIN_PROBABILITY, survivalPrior));
        return new WinProbabilityModel.Params(
                clamped,
                BotConfig.ScoreMax.DEFAULT_PRIOR_WEIGHT_FLOOR,
                BotConfig.ScoreMax.DEFAULT_PRIOR_WEIGHT_EXPONENT,
                BotConfig.ScoreMax.DEFAULT_ENERGY_LEAD_SCALE,
                BotConfig.ScoreMax.DEFAULT_ENERGY_LEAD_OFFSET,
                BotConfig.ScoreMax.DEFAULT_LOW_ENERGY_UNCERTAINTY_SCALE);
    }

    double adjustedSurvivalPrior(double survivalPrior) {
        if (info == null || info.getRobot() == null || info.getDataStore() == null) {
            return survivalPrior;
        }
        if (!info.getDataStore().isFirstBattleForTrackedOpponent()) {
            return survivalPrior;
        }
        if (scoreMaxSelectedRoundCount <= 0) {
            return survivalPrior;
        }
        double optimisticTarget = Math.max(
                survivalPrior,
                BotConfig.ScoreMax.FIRST_BATTLE_SCOREMAX_PRIOR_TARGET);
        double blend = firstBattleScoreMaxPriorBlend(scoreMaxSelectedRoundCount - 1);
        return survivalPrior + blend * (optimisticTarget - survivalPrior);
    }

    static double firstBattleScoreMaxPriorBlend(int scoreMaxRoundIndex) {
        if (scoreMaxRoundIndex <= 0) {
            return 1.0;
        }
        if (scoreMaxRoundIndex >= BotConfig.ScoreMax.FIRST_BATTLE_SCOREMAX_PRIOR_ROUNDS - 1) {
            return 0.0;
        }
        double t = scoreMaxRoundIndex
                / (double) (BotConfig.ScoreMax.FIRST_BATTLE_SCOREMAX_PRIOR_ROUNDS - 1);
        double smooth = t * t * (3.0 - 2.0 * t);
        return 1.0 - smooth;
    }

    void noteScoreMaxRoundSelection() {
        if (info == null || info.getRobot() == null) {
            return;
        }
        int roundNum = info.getRobot().getRoundNum();
        if (roundNum < 0 || roundNum == lastCountedScoreMaxRound) {
            return;
        }
        lastCountedScoreMaxRound = roundNum;
        scoreMaxSelectedRoundCount++;
    }

    private double currentEnemyEnergyForScoring() {
        EnemyInfo enemy = info.getEnemy();
        if (enemy == null || !enemy.alive || !enemy.seenThisRound) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0.0, enemy.energy);
    }
}
