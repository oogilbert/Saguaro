package oog.mega.saguaro.mode.shotdodger;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.learning.RoundOutcomeProfile;
import oog.mega.saguaro.mode.ModeId;
import oog.mega.saguaro.mode.ModePerformanceProfile;

final class WavePoisonRoundOutcomeProfile implements RoundOutcomeProfile {
    static final WavePoisonRoundOutcomeProfile INSTANCE = new WavePoisonRoundOutcomeProfile();

    private WavePoisonRoundOutcomeProfile() {
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
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getPersistedStats(ModeId.WAVE_POISON);
        double denominator = stats.totalOurScore + stats.totalOpponentScore;
        if (denominator > 0.0) {
            return stats.totalOurScore / denominator;
        }
        return BotConfig.Learning.DEFAULT_SCORE_SHARE_PRIOR;
    }
}
