package oog.mega.saguaro.render;

import java.util.Collections;
import java.util.List;

public final class RenderState {
    public final List<PathOverlay> pathOverlays;
    public final boolean renderDefaultWaveGraphics;

    public RenderState(List<PathOverlay> pathOverlays) {
        this(pathOverlays, true);
    }

    public RenderState(List<PathOverlay> pathOverlays,
                       boolean renderDefaultWaveGraphics) {
        this.pathOverlays = pathOverlays != null ? pathOverlays : Collections.<PathOverlay>emptyList();
        this.renderDefaultWaveGraphics = renderDefaultWaveGraphics;
    }
}
