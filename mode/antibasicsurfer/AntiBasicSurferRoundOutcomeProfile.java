package oog.mega.saguaro.mode.antibasicsurfer;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.mode.ModePerformanceProfile;

final class AntiBasicSurferRoundOutcomeProfile implements RoundOutcomeProfile {
    private final ModeId modeId;

    AntiBasicSurferRoundOutcomeProfile(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("AntiBasicSurferRoundOutcomeProfile requires a non-null mode id");
        }
        this.modeId = modeId;
    }

    @Override
    public void startBattle() {
    }

    @Override
    public int recordWin() {
        return 0;
    }

    @Override
    public int recordLoss() {
        return 0;
    }

    @Override
    public double getSurvivalPrior() {
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getPersistedStats(modeId);
        double denominator = stats.totalOurScore + stats.totalOpponentScore;
        if (denominator > 0.0) {
            return stats.totalOurScore / denominator;
        }
        return BotConfig.Learning.DEFAULT_SCORE_SHARE_PRIOR;
    }
}
