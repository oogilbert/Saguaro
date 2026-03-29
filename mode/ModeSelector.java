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
        return selectMode(candidateModes, candidateModes);
    }

    ModeId selectMode(ModeId[] candidateModes, ModeId[] comparisonModes) {
        ModePosterior[] candidatePosteriors = candidateModePosteriors(candidateModes);
        if (candidatePosteriors.length < 1) {
            throw new IllegalArgumentException("Mode selection requires at least one admissible mode");
        }
        ModePosterior[] comparisonPosteriors = candidateModePosteriors(comparisonModes);
        List<ModePosterior> qualified = new ArrayList<>();
        for (ModePosterior posterior : candidatePosteriors) {
            if (!isDisqualified(posterior, comparisonPosteriors)) {
                qualified.add(posterior);
            }
        }
        if (qualified.isEmpty()) {
            return selectMostUncertain(candidatePosteriors).modeId;
        }
        ModePosterior[] selectable = qualified.toArray(new ModePosterior[0]);
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
        double comparisonMean = comparisonMean(posteriorMean);
        double selectionUncertainty = comparisonUncertainty(totalScore, priorScale);
        double comparisonLowerBound =
                comparisonMean - BotConfig.ModeSelection.CONFIDENCE_SCALE * selectionUncertainty;
        double comparisonUpperBound =
                comparisonMean + BotConfig.ModeSelection.CONFIDENCE_SCALE * selectionUncertainty;
        double lowerBound = probabilityFromComparisonValue(comparisonLowerBound);
        double upperBound = probabilityFromComparisonValue(comparisonUpperBound);
        double intervalWidth = upperBound - lowerBound;
        return new ModePosterior(
                modeId,
                posteriorMean,
                totalScore <= 0.0,
                intervalWidth,
                selectionUncertainty,
                lowerBound,
                upperBound,
                comparisonMean,
                comparisonUpperBound);
    }

    private static double clampProbabilityToUnitInterval(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double comparisonMean(double probability) {
        double openProbability = comparisonOpenProbability(probability);
        return Math.log(openProbability / (1.0 - openProbability));
    }

    private static double comparisonUncertainty(double totalScore, double priorScale) {
        double effectiveEvidence =
                MODE_UNCERTAINTY_PRIOR_EPSILON
                        + (BotConfig.ModeSelection.UNCERTAINTY_PRIOR_ALPHA
                        + BotConfig.ModeSelection.UNCERTAINTY_PRIOR_BETA) * priorScale
                        + totalScore / BotConfig.ModeSelection.POSTERIOR_SCORE_UNIT;
        return 1.0 / Math.sqrt(Math.max(MODE_UNCERTAINTY_PRIOR_EPSILON, effectiveEvidence));
    }

    private static double probabilityFromComparisonValue(double comparisonValue) {
        return clampProbabilityToUnitInterval(sigmoid(comparisonValue) + BotConfig.ModeSelection.COMPARISON_SHARE_OFFSET);
    }

    private static double comparisonOpenProbability(double probability) {
        double shiftedProbability = probability - BotConfig.ModeSelection.COMPARISON_SHARE_OFFSET;
        return Math.max(
                MODE_UNCERTAINTY_PRIOR_EPSILON,
                Math.min(1.0 - MODE_UNCERTAINTY_PRIOR_EPSILON, shiftedProbability));
    }

    private static double sigmoid(double x) {
        if (x >= 0.0) {
            double e = Math.exp(-x);
            return 1.0 / (1.0 + e);
        }
        double e = Math.exp(x);
        return e / (1.0 + e);
    }

    private static boolean prefersSelection(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.selectionUncertainty > incumbent.selectionUncertainty) {
            return true;
        }
        if (candidate.selectionUncertainty < incumbent.selectionUncertainty) {
            return false;
        }
        if (candidate.untested && incumbent.untested) {
            int candidatePriority = untestedSelectionPriority(candidate.modeId);
            int incumbentPriority = untestedSelectionPriority(incumbent.modeId);
            if (candidatePriority < incumbentPriority) {
                return true;
            }
            if (candidatePriority > incumbentPriority) {
                return false;
            }
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

    private static ModePosterior selectMostUncertain(ModePosterior[] posteriors) {
        if (posteriors == null || posteriors.length < 1) {
            throw new IllegalArgumentException("Most-uncertain fallback requires at least one mode");
        }
        ModePosterior best = posteriors[0];
        for (int i = 1; i < posteriors.length; i++) {
            if (prefersUncertaintyOnly(posteriors[i], best)) {
                best = posteriors[i];
            }
        }
        return best;
    }

    private static boolean prefersUncertaintyOnly(ModePosterior candidate, ModePosterior incumbent) {
        if (candidate.selectionUncertainty > incumbent.selectionUncertainty) {
            return true;
        }
        if (candidate.selectionUncertainty < incumbent.selectionUncertainty) {
            return false;
        }
        if (candidate.untested && incumbent.untested) {
            int candidatePriority = untestedSelectionPriority(candidate.modeId);
            int incumbentPriority = untestedSelectionPriority(incumbent.modeId);
            if (candidatePriority < incumbentPriority) {
                return true;
            }
            if (candidatePriority > incumbentPriority) {
                return false;
            }
        }
        return candidate.modeId.ordinal() < incumbent.modeId.ordinal();
    }

    private static int untestedSelectionPriority(ModeId modeId) {
        if (modeId == ModeId.BULLET_SHIELD) {
            return 0;
        }
        if (modeId == ModeId.MOVING_BULLET_SHIELD) {
            return 1;
        }
        if (modeId == ModeId.WAVE_POISON) {
            return 2;
        }
        return 3;
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
        final boolean untested;
        final double intervalWidth;
        final double selectionUncertainty;
        final double lowerBound;
        final double upperBound;
        final double comparisonMean;
        final double comparisonUpperBound;

        ModePosterior(ModeId modeId,
                      double posteriorMean,
                      boolean untested,
                      double intervalWidth,
                      double selectionUncertainty,
                      double lowerBound,
                      double upperBound,
                      double comparisonMean,
                      double comparisonUpperBound) {
            this.modeId = modeId;
            this.posteriorMean = posteriorMean;
            this.untested = untested;
            this.intervalWidth = intervalWidth;
            this.selectionUncertainty = selectionUncertainty;
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
            this.comparisonMean = comparisonMean;
            this.comparisonUpperBound = comparisonUpperBound;
        }
    }
}
