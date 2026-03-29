package oog.mega.saguaro.mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import oog.mega.saguaro.BotConfig;

final class ModeSelector {
    private static final double MODE_UNCERTAINTY_PRIOR_EPSILON = 1e-6;

    private final ModeRoundScoreTracker roundScoreTracker;

    ModeSelector(ModeRoundScoreTracker roundScoreTracker) {
        if (roundScoreTracker == null) {
            throw new IllegalArgumentException("Mode selector requires a non-null round score tracker");
        }
        this.roundScoreTracker = roundScoreTracker;
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

    ModeId selectMode(ModeId[] candidateModes) {
        ModePosterior[] posteriors = candidateModePosteriors(candidateModes);
        if (posteriors.length < 1) {
            throw new IllegalArgumentException("Mode selection requires at least one admissible mode");
        }
        List<ModePosterior> qualified = new ArrayList<>();
        for (ModePosterior posterior : posteriors) {
            if (!isDisqualified(posterior, posteriors)) {
                qualified.add(posterior);
            }
        }
        ModePosterior[] selectable = qualified.isEmpty()
                ? posteriors
                : qualified.toArray(new ModePosterior[0]);
        ModePosterior best = selectable[0];
        for (int i = 1; i < selectable.length; i++) {
            if (prefersSelection(selectable[i], best)) {
                best = selectable[i];
            }
        }
        return best.modeId;
    }

    boolean isModeDisqualified(ModeId modeId, ModeId[] candidateModes) {
        ModePosterior[] posteriors = candidateModePosteriors(candidateModes);
        ModePosterior current = findPosterior(posteriors, modeId);
        return current == null || isDisqualified(current, posteriors);
    }

    private ModePosterior[] candidateModePosteriors(ModeId[] candidateModes) {
        List<ModePosterior> posteriors = new ArrayList<>();
        if (candidateModes == null) {
            return new ModePosterior[0];
        }
        for (ModeId modeId : candidateModes) {
            if (modeId != null) {
                posteriors.add(estimateLiveMode(modeId));
            }
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

    private static boolean isDisqualified(ModePosterior candidate, ModePosterior[] posteriors) {
        if (candidate == null || posteriors == null || posteriors.length < 2) {
            return false;
        }
        double bestOtherComparisonMean = Double.NEGATIVE_INFINITY;
        for (ModePosterior other : posteriors) {
            if (other.modeId == candidate.modeId) {
                continue;
            }
            if (other.comparisonMean > bestOtherComparisonMean) {
                bestOtherComparisonMean = other.comparisonMean;
            }
        }
        return candidate.comparisonUpperBound + 1e-9 < bestOtherComparisonMean;
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
        double posteriorMean = totalScore > 0.0
                ? (effectiveTotalScore > 0.0 ? (totalOurScore + priorOurScore) / effectiveTotalScore : 1.0)
                : BotConfig.ModeSelection.UNTESTED_MODE_MEAN;
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
        double evidenceOnlyShareUncertainty = 0.5 / Math.sqrt(uncertaintyAlpha + uncertaintyBeta + 1.0);
        double lowerBound = clampProbabilityToUnitInterval(
                posteriorMean - BotConfig.ModeSelection.CONFIDENCE_SCALE * shareUncertainty);
        double upperBound = clampProbabilityToUnitInterval(
                posteriorMean + BotConfig.ModeSelection.CONFIDENCE_SCALE * shareUncertainty);
        double intervalWidth = upperBound - lowerBound;
        double comparisonMean = topHeavyComparisonMean(posteriorMean);
        double comparisonUpperBound =
                comparisonMean + BotConfig.ModeSelection.CONFIDENCE_SCALE * evidenceOnlyShareUncertainty;
        return new ModePosterior(
                modeId,
                posteriorMean,
                intervalWidth,
                evidenceOnlyShareUncertainty,
                lowerBound,
                upperBound,
                comparisonMean,
                comparisonUpperBound);
    }

    private static double clampProbabilityToUnitInterval(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double topHeavyComparisonMean(double probability) {
        double shiftedProbability = Math.max(0.0, probability - BotConfig.ModeSelection.COMPARISON_SHARE_OFFSET);
        if (shiftedProbability <= 0.0) {
            return 0.0;
        }
        double openProbability = Math.min(1.0 - MODE_UNCERTAINTY_PRIOR_EPSILON, shiftedProbability);
        return openProbability / (1.0 - openProbability);
    }

    private static boolean prefersSelection(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.selectionUncertainty > incumbent.selectionUncertainty) {
            return true;
        }
        if (candidate.selectionUncertainty < incumbent.selectionUncertainty) {
            return false;
        }
        int candidatePriority = selectionPriority(candidate.modeId);
        int incumbentPriority = selectionPriority(incumbent.modeId);
        if (candidatePriority < incumbentPriority) {
            return true;
        }
        if (candidatePriority > incumbentPriority) {
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
        return candidate.modeId.ordinal() < incumbent.modeId.ordinal();
    }

    private static int selectionPriority(ModeId modeId) {
        if (modeId == ModeId.BULLET_SHIELD) {
            return 0;
        }
        if (modeId == ModeId.WAVE_POISON) {
            return 1;
        }
        return 2;
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

    private static final class ModePosterior {
        final ModeId modeId;
        final double posteriorMean;
        final double intervalWidth;
        final double selectionUncertainty;
        final double lowerBound;
        final double upperBound;
        final double comparisonMean;
        final double comparisonUpperBound;

        ModePosterior(ModeId modeId,
                      double posteriorMean,
                      double intervalWidth,
                      double selectionUncertainty,
                      double lowerBound,
                      double upperBound,
                      double comparisonMean,
                      double comparisonUpperBound) {
            this.modeId = modeId;
            this.posteriorMean = posteriorMean;
            this.intervalWidth = intervalWidth;
            this.selectionUncertainty = selectionUncertainty;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.comparisonMean = comparisonMean;
            this.comparisonUpperBound = comparisonUpperBound;
        }
    }
}
