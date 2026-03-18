package oog.mega.saguaro.math;

public final class MirroredGuessFactorDistribution implements GuessFactorDistribution {
    private final GuessFactorDistribution delegate;

    public MirroredGuessFactorDistribution(GuessFactorDistribution delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("MirroredGuessFactorDistribution requires a delegate");
        }
        this.delegate = delegate;
    }

    @Override
    public double density(double gf) {
        return delegate.density(-gf);
    }

    @Override
    public double integrate(double gfStart, double gfEnd) {
        return delegate.integrate(-gfEnd, -gfStart);
    }

    @Override
    public double findPeakGF() {
        return -delegate.findPeakGF();
    }

    @Override
    public double findValleyGF() {
        return -delegate.findValleyGF();
    }
}
