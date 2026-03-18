package oog.mega.saguaro.math;

public final class GuessFactorDistributionHandle {
    private final GuessFactorDistribution exactDistribution;
    private GuessFactorDistribution queryDistribution;

    public GuessFactorDistributionHandle(GuessFactorDistribution exactDistribution) {
        this.exactDistribution = exactDistribution;
    }

    public static GuessFactorDistributionHandle orNull(GuessFactorDistribution exactDistribution) {
        return exactDistribution != null ? new GuessFactorDistributionHandle(exactDistribution) : null;
    }

    public GuessFactorDistribution exact() {
        return exactDistribution;
    }

    public GuessFactorDistribution query() {
        if (queryDistribution == null) {
            queryDistribution = exactDistribution instanceof DefaultDistribution
                    ? exactDistribution
                    : new SampledGuessFactorDistribution(exactDistribution);
        }
        return queryDistribution;
    }
}
