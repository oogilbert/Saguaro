package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.mode.ModePerformanceProfile;

final class AntiSurferRoundOutcomeProfile implements RoundOutcomeProfile {
    static final AntiSurferRoundOutcomeProfile INSTANCE = new AntiSurferRoundOutcomeProfile();

    private AntiSurferRoundOutcomeProfile() {
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
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getPersistedStats(ModeId.ANTI_SURFER);
        double denominator = stats.totalOurScore + stats.totalOpponentScore;
        if (denominator > 0.0) {
            return stats.totalOurScore / denominator;
        }
        return BotConfig.Learning.DEFAULT_SCORE_SHARE_PRIOR;
    }
}
