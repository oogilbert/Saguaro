package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;
import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.WaveContextFeatures;
import oog.mega.saguaro.math.GuessFactorDistribution;

final class AlternatingMiniPatternRepeaterExpert {
    private AlternatingMiniPatternRepeaterExpert() {
    }

    static GuessFactorDistribution createGunDistribution(WaveContextFeatures.WaveContext context,
                                                         EnemyInfo enemy) {
        ExpertPrediction prediction = createGunPrediction(context, enemy);
        return prediction != null ? prediction.distribution : null;
    }

    static GuessFactorDistribution createMovementDistribution(WaveContextFeatures.WaveContext context,
                                                              Info info) {
        ExpertPrediction prediction = createMovementPrediction(context, info);
        return prediction != null ? prediction.distribution : null;
    }

    static ExpertPrediction createGunPrediction(WaveContextFeatures.WaveContext context,
                                                EnemyInfo enemy) {
        if (enemy == null) {
            return null;
        }
        return createPrediction(
                context,
                BotConfig.Learning.DEFAULT_TARGETING_KDE_BANDWIDTH,
                new PatternReplayExpertSupport.MotionHistorySource() {
                    @Override
                    public int size() {
                        return enemy.getMotionHistorySize();
                    }

                    @Override
                    public double velocityAt(int index) {
                        return enemy.getMotionHistoryVelocity(index);
                    }

                    @Override
                    public double headingDeltaAt(int index) {
                        return enemy.getMotionHistoryHeadingDelta(index);
                    }
                });
    }

    static ExpertPrediction createMovementPrediction(WaveContextFeatures.WaveContext context,
                                                     Info info) {
        if (info == null) {
            return null;
        }
        return createPrediction(
                context,
                BotConfig.Learning.DEFAULT_MOVEMENT_KDE_BANDWIDTH,
                new PatternReplayExpertSupport.MotionHistorySource() {
                    @Override
                    public int size() {
                        return info.getRobotMotionHistorySize();
                    }

                    @Override
                    public double velocityAt(int index) {
                        return info.getRobotMotionHistoryVelocity(index);
                    }

                    @Override
                    public double headingDeltaAt(int index) {
                        return info.getRobotMotionHistoryHeadingDelta(index);
                    }
                });
    }

    private static ExpertPrediction createPrediction(WaveContextFeatures.WaveContext context,
                                                     double bandwidth,
                                                     PatternReplayExpertSupport.MotionHistorySource history) {
        return PatternReplayExpertSupport.createPrediction(
                context,
                bandwidth,
                history,
                cooldownTicksForBulletSpeed(context.bulletSpeed),
                false,
                true,
                "Alternating mini-pattern expert");
    }

    private static int cooldownTicksForBulletSpeed(double bulletSpeed) {
        double firePower = (20.0 - bulletSpeed) / 3.0;
        return Math.max(1, (int) Math.ceil((1.0 + firePower / 5.0) / 0.1));
    }
}
