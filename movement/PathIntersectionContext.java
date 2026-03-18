package oog.mega.saguaro.movement;

import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;

public final class PathIntersectionContext {
    public final long startTime;
    public final double startX;
    public final double startY;
    public final List<Wave> wavesToScore;
    public final Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache;

    public PathIntersectionContext(long startTime,
                                   double startX,
                                   double startY,
                                   List<Wave> wavesToScore,
                                   Map<Wave, List<BulletShadowUtil.ShadowInterval>> shadowCache) {
        this.startTime = startTime;
        this.startX = startX;
        this.startY = startY;
        this.wavesToScore = wavesToScore;
        this.shadowCache = shadowCache;
    }
}
