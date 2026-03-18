package oog.mega.saguaro.movement;

import java.util.List;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.GuessFactorDistribution;

public final class PathWaveIntersection {
    public final Wave wave;
    public final long firstContactTime;
    public final double referenceBearing;
    public final double mea;
    public final List<BulletShadowUtil.WeightedGfInterval> exposedGfIntervals;
    public final GuessFactorDistribution distribution;

    public PathWaveIntersection(Wave wave,
                                long firstContactTime,
                                double referenceBearing,
                                double mea,
                                List<BulletShadowUtil.WeightedGfInterval> exposedGfIntervals,
                                GuessFactorDistribution distribution) {
        this.wave = wave;
        this.firstContactTime = firstContactTime;
        this.referenceBearing = referenceBearing;
        this.mea = mea;
        this.exposedGfIntervals = exposedGfIntervals;
        this.distribution = distribution;
    }
}
