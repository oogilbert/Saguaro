package oog.mega.saguaro.mode;

import java.util.Locale;

import oog.mega.saguaro.info.state.RobocodeScoreUtil;

final class ModeRoundScoreTracker {
    private final RobocodeScoreUtil.HitScoreScratch hitScoreScratch = new RobocodeScoreUtil.HitScoreScratch();
    private Segment currentSegment;
    private double trackedBattleOurScore;
    private double trackedBattleOpponentScore;
    private double ourCreditedDamageOnEnemyThisRound;
    private double enemyCreditedDamageOnUsThisRound;
    private boolean ourKillBonusAppliedThisRound;
    private boolean enemyKillBonusAppliedThisRound;
    private boolean roundEndedObserved;
    private boolean roundResultObserved;
    private double pendingRoundOurScore;
    private double pendingRoundOpponentScore;

    void startBattle() {
        currentSegment = null;
        trackedBattleOurScore = 0.0;
        trackedBattleOpponentScore = 0.0;
        clearRoundDamageState();
        clearPendingRoundState();
    }

    void startRound() {
        finalizePendingRoundIfNeeded();
        currentSegment = null;
        trackedBattleOurScore = 0.0;
        trackedBattleOpponentScore = 0.0;
        clearRoundDamageState();
        clearPendingRoundState();
    }

    void activateMode(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Round score tracker requires a non-null mode id");
        }
        if (currentSegment != null && currentSegment.modeId == modeId) {
            return;
        }
        finalizeCurrentSegment();
        currentSegment = new Segment(modeId);
    }

    void onBulletHit(double bulletPower, double enemyEnergyBeforeHit) {
        if (currentSegment == null) {
            return;
        }
        RobocodeScoreUtil.scoreBulletHit(
                bulletPower,
                enemyEnergyBeforeHit,
                ourCreditedDamageOnEnemyThisRound,
                ourKillBonusAppliedThisRound,
                hitScoreScratch);
        currentSegment.ourScore += hitScoreScratch.scoreDelta;
        trackedBattleOurScore += hitScoreScratch.scoreDelta;
        ourCreditedDamageOnEnemyThisRound = hitScoreScratch.cumulativeCreditedDamage;
        ourKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    void onHitByBullet(double bulletPower, double ourEnergyBeforeHit, boolean forgivenThisHit) {
        if (currentSegment == null) {
            return;
        }
        RobocodeScoreUtil.scoreBulletHit(
                bulletPower,
                ourEnergyBeforeHit,
                enemyCreditedDamageOnUsThisRound,
                enemyKillBonusAppliedThisRound,
                hitScoreScratch);
        Segment segment = currentSegment;
        segment.opponentScore += hitScoreScratch.scoreDelta;
        trackedBattleOpponentScore += hitScoreScratch.scoreDelta;
        enemyCreditedDamageOnUsThisRound = hitScoreScratch.cumulativeCreditedDamage;
        if (forgivenThisHit) {
            segment.forgivenOpponentScore += hitScoreScratch.creditedDamage;
        }
        enemyKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    void onHitRobot(boolean myFault, double enemyEnergyBeforeCollision, double ourEnergyBeforeCollision) {
        if (currentSegment == null) {
            return;
        }
        Segment segment = currentSegment;
        if (myFault) {
            RobocodeScoreUtil.scoreRamHit(
                    enemyEnergyBeforeCollision,
                    ourCreditedDamageOnEnemyThisRound,
                    ourKillBonusAppliedThisRound,
                    hitScoreScratch);
            segment.ourScore += hitScoreScratch.scoreDelta;
            trackedBattleOurScore += hitScoreScratch.scoreDelta;
            ourCreditedDamageOnEnemyThisRound = hitScoreScratch.cumulativeCreditedDamage;
            ourKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
            return;
        }

        RobocodeScoreUtil.scoreRamHit(
                ourEnergyBeforeCollision,
                enemyCreditedDamageOnUsThisRound,
                enemyKillBonusAppliedThisRound,
                hitScoreScratch);
        segment.opponentScore += hitScoreScratch.scoreDelta;
        trackedBattleOpponentScore += hitScoreScratch.scoreDelta;
        enemyCreditedDamageOnUsThisRound = hitScoreScratch.cumulativeCreditedDamage;
        enemyKillBonusAppliedThisRound = hitScoreScratch.killBonusApplied;
    }

    void onRoundEnded(double actualOurScore, double actualOpponentScore) {
        roundEndedObserved = true;
        pendingRoundOurScore = actualOurScore;
        pendingRoundOpponentScore = actualOpponentScore;
        if (roundResultObserved) {
            finalizeRound(actualOurScore, actualOpponentScore);
        }
    }

    void onRoundResult(double actualOurScore, double actualOpponentScore) {
        roundResultObserved = true;
        if (roundEndedObserved) {
            finalizeRound(actualOurScore, actualOpponentScore);
        }
    }

    double getLiveOurScore(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Live round score lookup requires a non-null mode id");
        }
        if (currentSegment == null || currentSegment.modeId != modeId) {
            return 0.0;
        }
        return currentSegment.ourScore;
    }

    double getLiveOpponentScore(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Live round score lookup requires a non-null mode id");
        }
        if (currentSegment == null || currentSegment.modeId != modeId) {
            return 0.0;
        }
        return currentSegment.opponentScore;
    }

    private void finalizePendingRoundIfNeeded() {
        if (!roundEndedObserved) {
            return;
        }
        finalizeRound(pendingRoundOurScore, pendingRoundOpponentScore);
    }

    private void finalizeRound(double actualOurScore, double actualOpponentScore) {
        double ourResidual = actualOurScore - trackedBattleOurScore;
        double opponentResidual = actualOpponentScore - trackedBattleOpponentScore;
        if (ourResidual < -1e-6 || opponentResidual < -1e-6) {
            System.out.println(String.format(
                    Locale.US,
                    "[WARN] Mode score tracker adjusted negative round residual: actual=(%.3f, %.3f) tracked=(%.3f, %.3f)",
                    actualOurScore,
                    actualOpponentScore,
                    trackedBattleOurScore,
                    trackedBattleOpponentScore));
        }
        if (currentSegment != null) {
            currentSegment.ourScore = Math.max(0.0, currentSegment.ourScore + ourResidual);
            currentSegment.opponentScore = Math.max(0.0, currentSegment.opponentScore + opponentResidual);
            currentSegment.forgivenOpponentScore =
                    Math.max(0.0, Math.min(currentSegment.forgivenOpponentScore, currentSegment.opponentScore));
            trackedBattleOurScore = actualOurScore;
            trackedBattleOpponentScore = actualOpponentScore;
        }
        finalizeCurrentSegment();
        clearPendingRoundState();
    }

    private void clearRoundDamageState() {
        ourCreditedDamageOnEnemyThisRound = 0.0;
        enemyCreditedDamageOnUsThisRound = 0.0;
        ourKillBonusAppliedThisRound = false;
        enemyKillBonusAppliedThisRound = false;
    }

    private void clearPendingRoundState() {
        roundEndedObserved = false;
        roundResultObserved = false;
        pendingRoundOurScore = 0.0;
        pendingRoundOpponentScore = 0.0;
    }

    private void finalizeCurrentSegment() {
        if (currentSegment == null) {
            return;
        }
        ModePerformanceProfile.recordSample(
                currentSegment.modeId,
                currentSegment.ourScore,
                currentSegment.opponentScore,
                Math.max(0.0, currentSegment.opponentScore - currentSegment.forgivenOpponentScore));
        currentSegment = null;
    }

    private static final class Segment {
        final ModeId modeId;
        double ourScore;
        double opponentScore;
        double forgivenOpponentScore;

        Segment(ModeId modeId) {
            this.modeId = modeId;
        }
    }
}
