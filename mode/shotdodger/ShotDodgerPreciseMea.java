package oog.mega.saguaro.mode.shotdodger;

import java.util.LinkedHashMap;
import java.util.Map;

import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class ShotDodgerPreciseMea {
    private static final int RANGE_CACHE_CAPACITY = 256;
    private static final Map<WaveContextFeatures.WaveContext, double[]> rangeCache =
            new LinkedHashMap<WaveContextFeatures.WaveContext, double[]>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<WaveContextFeatures.WaveContext, double[]> eldest) {
                    return size() > RANGE_CACHE_CAPACITY;
                }
            };

    private ShotDodgerPreciseMea() {
    }

    static synchronized double[] range(WaveContextFeatures.WaveContext context) {
        double[] cachedRange = rangeCache.get(context);
        if (cachedRange != null) {
            return cachedRange;
        }
        double[] computedRange = EscapeAheadExpert.preciseGfRange(context);
        rangeCache.put(context, computedRange);
        return computedRange;
    }

    static double clampGf(WaveContextFeatures.WaveContext context, double gf) {
        double[] preciseRange = range(context);
        return Math.max(preciseRange[0], Math.min(preciseRange[1], gf));
    }

    static synchronized void clearCache() {
        rangeCache.clear();
    }
}
