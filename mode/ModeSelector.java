package oog.mega.saguaro.mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import oog.mega.saguaro.BotConfig;
import oog.mega.saguaro.info.Info;

final class ModeSelector {
    private static final double MODE_UNCERTAINTY_PRIOR_EPSILON = 1e-6;
    private static final double MODE_POSTERIOR_PROBABILITY_EPSILON = 1e-9;

    private final ModeRoundScoreTracker roundScoreTracker;
    private Info info;

    ModeSelector(ModeRoundScoreTracker roundScoreTracker) {
        if (roundScoreTracker == null) {
            throw new IllegalArgumentException("Mode selector requires a non-null round score tracker");
        }
        this.roundScoreTracker = roundScoreTracker;
    }

    void setInfo(Info info) {
        this.info = info;
    }

    static String describeModeEstimate(ModeId modeId) {
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getCombinedStats(modeId);
        ModePosterior estimate = estimateMode(modeId, stats);
        return String.format(
                Locale.US,
                "share %.1f%% [%.1f%%, %.1f%%], raw %.0f-%.0f",
                estimate.posteriorMean * 100.0,
                estimate.lowerBound * 100.0,
                estimate.upperBound * 100.0,
                stats.totalOurScore,
                stats.totalOpponentScore);
    }

    ModeId chooseOpeningMode(boolean bulletShieldRetiredForBattle) {
        return selectOpeningMode(admissibleModePosteriors(bulletShieldRetiredForBattle));
    }

    ModeId chooseModeForSwitch(ModeId activeModeId, boolean bulletShieldRetiredForBattle) {
        ModePosterior[] posteriors = admissibleModePosteriors(bulletShieldRetiredForBattle);
        ModePosterior current = findPosterior(posteriors, activeModeId);
        if (current == null) {
            return selectOpeningMode(posteriors);
        }
        double bestOtherComparisonMean = bestOtherComparisonMean(posteriors, activeModeId);
        if (current.comparisonUpperBound >= bestOtherComparisonMean) {
            return activeModeId;
        }
        ModePosterior[] alternatives = excludeMode(posteriors, activeModeId);
        if (alternatives.length == 0) {
            return activeModeId;
        }
        return selectOpeningMode(alternatives);
    }

    private ModePosterior[] admissibleModePosteriors(boolean bulletShieldRetiredForBattle) {
        List<ModePosterior> posteriors = new ArrayList<>();
        posteriors.add(estimateLiveMode(ModeId.SCORE_MAX));
        if (!bulletShieldRetiredForBattle) {
            posteriors.add(estimateLiveMode(ModeId.BULLET_SHIELD));
        }
        if (isPerfectPredictionAdmissible(bulletShieldRetiredForBattle)) {
            posteriors.add(estimateLiveMode(ModeId.PERFECT_PREDICTION));
        }
        if (isShotDodgerAdmissible(bulletShieldRetiredForBattle)) {
            posteriors.add(estimateLiveMode(ModeId.SHOT_DODGER));
        }
        return posteriors.toArray(new ModePosterior[0]);
    }

    private ModePosterior estimateLiveMode(ModeId modeId) {
        if (modeId == null) {
            throw new IllegalArgumentException("Live mode estimate requires a non-null mode id");
        }
        ModePerformanceProfile.ModeStatsSnapshot stats = ModePerformanceProfile.getCombinedStats(modeId);
        double totalOurScore = stats.totalOurScore + roundScoreTracker.getLiveOurScore(modeId);
        double totalOpponentScore = stats.totalOpponentScore + roundScoreTracker.getLiveOpponentScore(modeId);
        return estimateMode(modeId, totalOurScore, totalOpponentScore);
    }

    private boolean isPerfectPredictionAdmissible(boolean bulletShieldRetiredForBattle) {
        if (info == null) {
            return false;
        }
        if (ModePerformanceProfile.hasAnyCombinedSamples(ModeId.PERFECT_PREDICTION)) {
            return true;
        }
        if (!info.isPerfectPredictionUnlocked()) {
            return false;
        }
        return !areLegalModesSettled(bulletShieldRetiredForBattle);
    }

    private boolean isShotDodgerAdmissible(boolean bulletShieldRetiredForBattle) {
        return info != null;
    }

    private boolean areLegalModesSettled(boolean bulletShieldRetiredForBattle) {
        List<ModePosterior> legal = new ArrayList<>();
        legal.add(estimateLiveMode(ModeId.SCORE_MAX));
        if (!bulletShieldRetiredForBattle) {
            legal.add(estimateLiveMode(ModeId.BULLET_SHIELD));
        }
        ModePosterior best = null;
        for (ModePosterior posterior : legal) {
            if (best == null || posterior.posteriorMean > best.posteriorMean) {
                best = posterior;
            }
        }
        if (best.intervalWidth >= BotConfig.ModeSelection.SETTLED_CI_WIDTH) {
            return false;
        }
        for (ModePosterior posterior : legal) {
            if (posterior.modeId != best.modeId && posterior.comparisonUpperBound > best.comparisonMean) {
                return false;
            }
        }
        return true;
    }

    private static ModeId selectOpeningMode(ModePosterior[] posteriors) {
        if (posteriors == null || posteriors.length < 1) {
            throw new IllegalArgumentException("Mode selection requires at least one admissible mode");
        }
        if (posteriors.length == 1) {
            return posteriors[0].modeId;
        }

        ModePosterior fallback = posteriors[0];
        for (ModePosterior posterior : posteriors) {
            if (prefersHigherMeanFallback(posterior, fallback)) {
                fallback = posterior;
            }
        }

        ModePosterior[] ordered = posteriors.clone();
        for (int i = 0; i < ordered.length - 1; i++) {
            int bestIndex = i;
            for (int j = i + 1; j < ordered.length; j++) {
                if (prefersOpeningOrder(ordered[j], ordered[bestIndex])) {
                    bestIndex = j;
                }
            }
            if (bestIndex != i) {
                ModePosterior swap = ordered[i];
                ordered[i] = ordered[bestIndex];
                ordered[bestIndex] = swap;
            }
        }

        for (ModePosterior candidate : ordered) {
            double bestOtherComparisonMean = Double.NEGATIVE_INFINITY;
            for (ModePosterior other : posteriors) {
                if (other.modeId == candidate.modeId) {
                    continue;
                }
                if (other.comparisonMean > bestOtherComparisonMean) {
                    bestOtherComparisonMean = other.comparisonMean;
                }
            }
            if (candidate.comparisonUpperBound > bestOtherComparisonMean
                    || (candidate.modeId == ModeId.BULLET_SHIELD
                    && nearlyEqual(candidate.comparisonUpperBound, bestOtherComparisonMean))) {
                return candidate.modeId;
            }
        }

        return fallback.modeId;
    }

    private static ModePosterior estimateMode(ModeId modeId, ModePerformanceProfile.ModeStatsSnapshot stats) {
        if (modeId == null || stats == null) {
            throw new IllegalArgumentException("Mode estimate requires non-null mode stats");
        }
        return estimateMode(modeId, stats.totalOurScore, stats.totalOpponentScore);
    }

    private static ModePosterior estimateMode(ModeId modeId, double totalOurScore, double totalOpponentScore) {
        if (modeId == null) {
            throw new IllegalArgumentException("Mode estimate requires a non-null mode id");
        }
        if (!(totalOurScore >= 0.0) || !(totalOpponentScore >= 0.0)) {
            throw new IllegalArgumentException("Mode estimate totals must be non-negative");
        }
        double totalScore = totalOurScore + totalOpponentScore;
        double priorScale = Math.max(0.0, 1.0 - totalScore / BotConfig.ModeSelection.PRIOR_FADE_SCORE);
        double priorOurScore = BotConfig.ModeSelection.PRIOR_SCORE * priorScale;
        double effectiveTotalScore = totalScore + priorOurScore;
        double posteriorMean = effectiveTotalScore > 0.0
                ? (totalOurScore + priorOurScore) / effectiveTotalScore
                : 1.0;
        double uncertaintyAlpha =
                MODE_UNCERTAINTY_PRIOR_EPSILON
                        + BotConfig.ModeSelection.UNCERTAINTY_PRIOR_ALPHA * priorScale
                        + totalOurScore / BotConfig.ModeSelection.POSTERIOR_SCORE_UNIT;
        double uncertaintyBeta =
                MODE_UNCERTAINTY_PRIOR_EPSILON
                        + BotConfig.ModeSelection.UNCERTAINTY_PRIOR_BETA * priorScale
                        + totalOpponentScore / BotConfig.ModeSelection.POSTERIOR_SCORE_UNIT;
        double variance = (uncertaintyAlpha * uncertaintyBeta)
                / ((uncertaintyAlpha + uncertaintyBeta)
                * (uncertaintyAlpha + uncertaintyBeta)
                * (uncertaintyAlpha + uncertaintyBeta + 1.0));
        double shareUncertainty = Math.sqrt(Math.max(0.0, variance));
        double clampedPosteriorMean = clampProbabilityToOpenInterval(posteriorMean);
        double comparisonMean = logit(clampedPosteriorMean);
        double comparisonUncertainty = shareUncertainty / (clampedPosteriorMean * (1.0 - clampedPosteriorMean));
        double comparisonLowerBound =
                comparisonMean - BotConfig.ModeSelection.CONFIDENCE_SCALE * comparisonUncertainty;
        double comparisonUpperBound =
                comparisonMean + BotConfig.ModeSelection.CONFIDENCE_SCALE * comparisonUncertainty;
        double lowerBound = sigmoid(comparisonLowerBound);
        double upperBound = sigmoid(comparisonUpperBound);
        double intervalWidth = upperBound - lowerBound;
        return new ModePosterior(
                modeId,
                posteriorMean,
                intervalWidth,
                lowerBound,
                upperBound,
                comparisonMean,
                comparisonUpperBound);
    }

    private static double clampProbabilityToOpenInterval(double value) {
        return Math.max(
                MODE_POSTERIOR_PROBABILITY_EPSILON,
                Math.min(1.0 - MODE_POSTERIOR_PROBABILITY_EPSILON, value));
    }

    private static double logit(double probability) {
        return Math.log(probability / (1.0 - probability));
    }

    private static double sigmoid(double x) {
        if (x >= 0.0) {
            double e = Math.exp(-x);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(x);
        return e / (1.0 + e);
    }

    private static boolean prefersHigherMeanFallback(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.posteriorMean > incumbent.posteriorMean) {
            return true;
        }
        if (candidate.posteriorMean < incumbent.posteriorMean) {
            return false;
        }
        return prefersBulletShieldTie(candidate.modeId, incumbent.modeId);
    }

    private static boolean prefersOpeningOrder(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.intervalWidth > incumbent.intervalWidth) {
            return true;
        }
        if (candidate.intervalWidth < incumbent.intervalWidth) {
            return false;
        }
        if (candidate.upperBound > incumbent.upperBound) {
            return true;
        }
        if (candidate.upperBound < incumbent.upperBound) {
            return false;
        }
        if (candidate.posteriorMean > incumbent.posteriorMean) {
            return true;
        }
        if (candidate.posteriorMean < incumbent.posteriorMean) {
            return false;
        }
        return prefersBulletShieldTie(candidate.modeId, incumbent.modeId);
    }

    private static boolean prefersBulletShieldTie(ModeId candidate, ModeId incumbent) {
        return candidate == ModeId.BULLET_SHIELD && incumbent != ModeId.BULLET_SHIELD;
    }

    private static boolean nearlyEqual(double first, double second) {
        return Math.abs(first - second) <= 1e-9;
    }

    private static ModePosterior findPosterior(ModePosterior[] posteriors, ModeId modeId) {
        if (posteriors == null || modeId == null) {
            return null;
        }
        for (ModePosterior posterior : posteriors) {
            if (posterior.modeId == modeId) {
                return posterior;
            }
        }
        return null;
    }

    private static double bestOtherComparisonMean(ModePosterior[] posteriors, ModeId currentModeId) {
        double bestOtherComparisonMean = Double.NEGATIVE_INFINITY;
        for (ModePosterior other : posteriors) {
            if (other.modeId == currentModeId) {
                continue;
            }
            if (other.comparisonMean > bestOtherComparisonMean) {
                bestOtherComparisonMean = other.comparisonMean;
            }
        }
        return bestOtherComparisonMean;
    }

    private static ModePosterior[] excludeMode(ModePosterior[] posteriors, ModeId modeId) {
        List<ModePosterior> filtered = new ArrayList<>();
        for (ModePosterior posterior : posteriors) {
            if (posterior.modeId != modeId) {
                filtered.add(posterior);
            }
        }
        return filtered.toArray(new ModePosterior[0]);
    }

    private static final class ModePosterior {
        final ModeId modeId;
        final double posteriorMean;
        final double intervalWidth;
        final double lowerBound;
        final double upperBound;
        final double comparisonMean;
        final double comparisonUpperBound;

        ModePosterior(ModeId modeId,
                      double posteriorMean,
                      double intervalWidth,
                      double lowerBound,
                      double upperBound,
                      double comparisonMean,
                      double comparisonUpperBound) {
            this.modeId = modeId;
            this.posteriorMean = posteriorMean;
            this.intervalWidth = intervalWidth;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.comparisonMean = comparisonMean;
            this.comparisonUpperBound = comparisonUpperBound;
        }
    }
}
