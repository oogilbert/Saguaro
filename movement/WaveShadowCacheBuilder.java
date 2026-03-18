package oog.mega.saguaro.movement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.MathUtils;

final class WaveShadowCacheBuilder {
    private final MovementEngine movement;

    WaveShadowCacheBuilder(MovementEngine movement) {
        if (movement == null) {
            throw new IllegalArgumentException("WaveShadowCacheBuilder requires non-null movement");
        }
        this.movement = movement;
    }

    Map<Wave, List<BulletShadowUtil.ShadowInterval>> buildBaseShadowCache(List<Wave> waves) {
        Map<Wave, List<BulletShadowUtil.ShadowInterval>> cache = new IdentityHashMap<>();
        for (Wave wave : waves) {
            if (!wave.isEnemy) {
                continue;
            }
            if (wave.isVirtual) {
                // Virtual waves are speculative, so skip shadow modeling to keep
                // pre-fire planning cheaper while preserving real-wave precision.
                cache.put(wave, java.util.Collections.emptyList());
            } else {
                cache.put(wave, wave.getShadowIntervals());
            }
        }
        return cache;
    }

    Map<Wave, MovementEngine.PrecomputedWaveData> buildPrecomputedWaveData(
            List<Wave> waves,
            Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache) {
        Map<Wave, MovementEngine.PrecomputedWaveData> precomputed = new IdentityHashMap<>();
        for (Wave wave : waves) {
            if (!wave.isEnemy || wave.isVirtual) {
                continue;
            }
            double referenceBearing = Math.atan2(
                    movement.waveReferenceX(wave) - wave.originX,
                    movement.waveReferenceY(wave) - wave.originY);
            double mea = MathUtils.maxEscapeAngle(wave.speed);
            List<BulletShadowUtil.ShadowInterval> waveShadows =
                    MovementEngine.shadowCacheForWave(shadowCache, wave);
            List<BulletShadowUtil.WeightedGfInterval> mergedShadowGfIntervals;
            if (waveShadows == null || waveShadows.isEmpty()) {
                mergedShadowGfIntervals = java.util.Collections.emptyList();
            } else {
                mergedShadowGfIntervals = BulletShadowUtil.mergeAndClipWeightedIntervals(
                        BulletShadowUtil.allGfIntervals(waveShadows, referenceBearing, mea), -1.0, 1.0);
            }
            precomputed.put(
                    wave,
                    new MovementEngine.PrecomputedWaveData(referenceBearing, mea, mergedShadowGfIntervals));
        }
        return precomputed;
    }
}

