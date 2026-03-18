package oog.mega.saguaro.movement;

import java.util.List;
import java.util.Map;

import oog.mega.saguaro.info.state.EnemyInfo;
import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.PhysicsUtil;

public final class PathGenerationContext {
    public final PhysicsUtil.PositionState startState;
    public final long currentTime;
    public final double bfWidth;
    public final double bfHeight;
    public final List<Wave> activeEnemyWaves;
    public final EnemyInfo opponent;
    public final double opponentReferenceX;
    public final double opponentReferenceY;
    public final List<Wave> scoringWaves;
    public final List<Wave> planningWaves;
    public final Map<Wave, List<BulletShadowUtil.ShadowInterval>> baseShadowCache;
    public int minPathTicks;

    public PathGenerationContext(PhysicsUtil.PositionState startState,
                                 long currentTime,
                                 double bfWidth,
                                 double bfHeight,
                                 List<Wave> activeEnemyWaves,
                                 EnemyInfo opponent,
                                 double opponentReferenceX,
                                 double opponentReferenceY,
                                 List<Wave> scoringWaves,
                                 List<Wave> planningWaves,
                                 Map<Wave, List<BulletShadowUtil.ShadowInterval>> baseShadowCache) {
        this.startState = startState;
        this.currentTime = currentTime;
        this.bfWidth = bfWidth;
        this.bfHeight = bfHeight;
        this.activeEnemyWaves = activeEnemyWaves;
        this.opponent = opponent;
        this.opponentReferenceX = opponentReferenceX;
        this.opponentReferenceY = opponentReferenceY;
        this.scoringWaves = scoringWaves;
        this.planningWaves = planningWaves;
        this.baseShadowCache = baseShadowCache;
    }
}
