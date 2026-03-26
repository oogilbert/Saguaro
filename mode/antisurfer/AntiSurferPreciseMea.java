package oog.mega.saguaro.mode.antisurfer;

import oog.mega.saguaro.info.wave.WaveContextFeatures;

final class AntiSurferPreciseMea {
    private AntiSurferPreciseMea() {
    }

    static double[] range(WaveContextFeatures.WaveContext context) {
        return EscapeAheadExpert.preciseGfRange(context);
    }

    static double clampGf(WaveContextFeatures.WaveContext context, double gf) {
        double[] preciseRange = range(context);
        return Math.max(preciseRange[0], Math.min(preciseRange[1], gf));
    }
}
