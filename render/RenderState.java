package oog.mega.saguaro.render;

import java.util.Collections;
import java.util.List;

public final class RenderState {
    public enum WaveRenderMode {
        FULL,
        TICKS_ONLY,
        NONE
    }

    public final List<PathOverlay> pathOverlays;
    public final boolean renderDefaultWaveGraphics;
    public final WaveRenderMode enemyWaveRenderMode;
    public final WaveRenderMode myWaveRenderMode;
    public final boolean highlightSelectedEnemyExpertTick;

    public RenderState(List<PathOverlay> pathOverlays) {
        this(pathOverlays, WaveRenderMode.FULL, WaveRenderMode.FULL, false);
    }

    public RenderState(List<PathOverlay> pathOverlays,
                       boolean renderDefaultWaveGraphics) {
        this(
                pathOverlays,
                renderDefaultWaveGraphics ? WaveRenderMode.FULL : WaveRenderMode.NONE,
                renderDefaultWaveGraphics ? WaveRenderMode.FULL : WaveRenderMode.NONE,
                false);
    }

    public RenderState(List<PathOverlay> pathOverlays,
                       WaveRenderMode enemyWaveRenderMode,
                       WaveRenderMode myWaveRenderMode,
                       boolean highlightSelectedEnemyExpertTick) {
        this.pathOverlays = pathOverlays != null ? pathOverlays : Collections.<PathOverlay>emptyList();
        this.enemyWaveRenderMode = enemyWaveRenderMode != null ? enemyWaveRenderMode : WaveRenderMode.FULL;
        this.myWaveRenderMode = myWaveRenderMode != null ? myWaveRenderMode : WaveRenderMode.FULL;
        this.highlightSelectedEnemyExpertTick = highlightSelectedEnemyExpertTick;
        this.renderDefaultWaveGraphics =
                this.enemyWaveRenderMode != WaveRenderMode.NONE || this.myWaveRenderMode != WaveRenderMode.NONE;
    }
}
