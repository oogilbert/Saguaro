package oog.mega.saguaro.info.wave;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.info.wave.BulletShadowUtil.ShadowInterval;
import oog.mega.saguaro.math.DefaultDistribution;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.MathUtils;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.math.RobotHitbox;
import oog.mega.saguaro.mode.ModeController;
import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import oog.mega.saguaro.movement.WaveStrategy;

public final class WaveRenderer {
    private static final int ARC_SEGMENTS = 160;
    private static final int PATH_NODE_DIAMETER = 5;
    private static final Color OUTSIDE_PRECISE_MEA_COLOR = new Color(120, 120, 120);
    private static final Color STRATEGY_MAX_CW_COLOR = new Color(70, 140, 255);
    private static final Color STRATEGY_MAX_CCW_COLOR = new Color(255, 165, 64);
    private static final Color STRATEGY_STRAIGHT_COLOR = new Color(80, 215, 140);
    private static final Color STRATEGY_MOVE_IN_COLOR = new Color(255, 95, 95);
    private static final Color STRATEGY_MOVE_OUT_COLOR = new Color(200, 120, 255);
    private static final Color STRATEGY_UNKNOWN_COLOR = new Color(185, 185, 185);
    private static final Color SELECTED_CONTINUATION_LINE_COLOR = new Color(255, 244, 120);
    private static final Color SELECTED_CONTINUATION_NODE_COLOR = new Color(255, 244, 120, 210);
    private static final Color FULL_SHADOW_COLOR = Color.BLACK;
    private static final Color HALF_SHADOW_COLOR = Color.GRAY;
    private static final List<BulletShadowUtil.WeightedGfInterval> NO_SHADOW_INTERVALS = Collections.emptyList();
    // Debug rendering still needs a visible probability band before learning data exists.
    private static final GuessFactorDistribution DEFAULT_RENDER_DISTRIBUTION = new DefaultDistribution();
    private static final Color[] INTERSECTION_TICK_COLORS = {
            new Color(255, 86, 86),
            new Color(255, 178, 64),
            new Color(255, 230, 120),
            new Color(133, 224, 133)
    };

    private static final class ProbabilitySamples {
        private final double[] values;
        private final double max;

        private ProbabilitySamples(double[] values, double max) {
            this.values = values;
            this.max = max;
        }
    }

    private static final class PathIntersectionLookup {
        private final int[] firstIntersectionIndexByTick;

        private PathIntersectionLookup(int[] firstIntersectionIndexByTick) {
            this.firstIntersectionIndexByTick = firstIntersectionIndexByTick;
        }

        private int intersectionIndexAt(int tickOffset) {
            return firstIntersectionIndexByTick[tickOffset];
        }
    }

    public void render(Graphics2D graphics,
                       long time,
                       double battlefieldWidth,
                       double battlefieldHeight,
                       List<Wave> enemyWaves,
                       List<Wave> myWaves,
                       CandidatePath selectedPath,
                       List<CandidatePath> selectedSafeSpotPaths,
                       List<PathWaveIntersection> selectedPathIntersections,
                       List<ModeController.DebugLine> debugLines,
                       boolean renderDefaultWaveGraphics) {
        if (graphics == null) {
            return;
        }
        if (renderDefaultWaveGraphics) {
            drawWaveArcs(
                    graphics,
                    time,
                    enemyWaves,
                    battlefieldWidth,
                    battlefieldHeight,
                    true);
            drawWaveArcs(
                    graphics,
                    time,
                    myWaves,
                    battlefieldWidth,
                    battlefieldHeight,
                    false);
            drawSelectedPath(graphics, time, selectedPath, selectedSafeSpotPaths, selectedPathIntersections);
        }
        drawDebugLines(graphics, debugLines);
    }

    private static void drawDebugLines(Graphics2D graphics, List<ModeController.DebugLine> debugLines) {
        if (debugLines == null || debugLines.isEmpty()) {
            return;
        }
        Stroke oldStroke = graphics.getStroke();
        float activeStrokeWidth = Float.NaN;
        for (ModeController.DebugLine line : debugLines) {
            if (line == null || line.color == null) {
                continue;
            }
            float strokeWidth = line.strokeWidth > 0.0f ? line.strokeWidth : 1.0f;
            if (strokeWidth != activeStrokeWidth) {
                graphics.setStroke(new BasicStroke(strokeWidth));
                activeStrokeWidth = strokeWidth;
            }
            graphics.setColor(line.color);
            graphics.drawLine(
                    (int) Math.round(line.x1),
                    (int) Math.round(line.y1),
                    (int) Math.round(line.x2),
                    (int) Math.round(line.y2));
        }
        graphics.setStroke(oldStroke);
    }

    private void drawWaveArcs(Graphics2D graphics,
                              long time,
                              List<Wave> waves,
                              double battlefieldWidth,
                              double battlefieldHeight,
                              boolean renderPersistentShadows) {
        Stroke oldStroke = graphics.getStroke();
        graphics.setStroke(new BasicStroke(1f));

        for (Wave wave : waves) {
            if (!hasWaveReference(wave)) {
                if (wave.isEnemy) {
                    throw new IllegalStateException("Enemy wave missing fire-time target reference");
                }
                continue;
            }
            double refX = wave.targetX;
            double refY = wave.targetY;

            double referenceBearing = Math.atan2(refX - wave.originX, refY - wave.originY);
            double mea = MathUtils.maxEscapeAngle(wave.speed);
            double radius = wave.getRadius(time);
            double dist = Math.hypot(refX - wave.originX, refY - wave.originY);
            double[] gfIntervalOffsets =
                    RobotHitbox.guessFactorIntervalOffsets(referenceBearing, dist, mea);
            double[] preciseGfRange = MathUtils.inFieldMaxEscapeGfRange(
                    wave.originX, wave.originY, refX, refY, wave.speed, battlefieldWidth, battlefieldHeight);
            List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals = renderPersistentShadows
                    ? activeShadowIntervals(wave, referenceBearing, mea)
                    : NO_SHADOW_INTERVALS;
            GuessFactorDistribution distribution = distributionForRender(wave);
            ProbabilitySamples samples = sampleArcProbabilities(
                    distribution, gfIntervalOffsets[0], gfIntervalOffsets[1]);
            drawWaveArcSegments(
                    graphics,
                    wave,
                    radius,
                    referenceBearing,
                    mea,
                    preciseGfRange[0],
                    preciseGfRange[1],
                    activeShadowIntervals,
                    samples.values,
                    samples.max);
        }

        graphics.setStroke(oldStroke);
    }

    private static double shadowWeightForSegment(double gfStart,
                                                 double gfEnd,
                                                 List<BulletShadowUtil.WeightedGfInterval> shadowIntervals) {
        double start = Math.min(gfStart, gfEnd);
        double end = Math.max(gfStart, gfEnd);
        double maxWeight = 0.0;
        for (BulletShadowUtil.WeightedGfInterval interval : shadowIntervals) {
            if (interval.endGf >= start && interval.startGf <= end) {
                maxWeight = Math.max(maxWeight, interval.weight);
            }
        }
        return maxWeight;
    }

    private void drawSelectedPath(Graphics2D graphics,
                                  long time,
                                  CandidatePath selectedPath,
                                  List<CandidatePath> selectedSafeSpotPaths,
                                  List<PathWaveIntersection> selectedPathIntersections) {
        if (selectedPath == null) {
            return;
        }

        Stroke oldStroke = graphics.getStroke();
        graphics.setStroke(new BasicStroke(2));
        if (selectedSafeSpotPaths != null) {
            for (CandidatePath path : selectedSafeSpotPaths) {
                if (path == null || path == selectedPath || path.trajectory == null) {
                    continue;
                }
                drawPath(
                        graphics,
                        path,
                        path.pathIntersections,
                        styleForPath(path, false),
                        nodeStyleForPath(path, false));
            }
        }

        List<PathWaveIntersection> intersections =
                selectedPathIntersections != null ? selectedPathIntersections : selectedPath.pathIntersections;
        PathIntersectionLookup selectedIntersectionLookup = buildPathIntersectionLookup(selectedPath, intersections);
        int firstSegmentLastTick = displayLastTick(selectedPath);
        drawPathSegment(
                graphics,
                selectedPath.trajectory,
                0,
                firstSegmentLastTick,
                selectedIntersectionLookup,
                styleForPath(selectedPath, true),
                nodeStyleForPath(selectedPath, true));
        int maxTick = selectedPath.trajectory.length() - 1;
        if (firstSegmentLastTick < maxTick) {
            drawPathSegment(
                    graphics,
                    selectedPath.trajectory,
                    firstSegmentLastTick,
                    maxTick,
                    selectedIntersectionLookup,
                    SELECTED_CONTINUATION_LINE_COLOR,
                    SELECTED_CONTINUATION_NODE_COLOR);
        }
        drawPrimaryTargetTick(graphics, time, selectedPath);

        graphics.setStroke(oldStroke);
    }

    private static double gfForSegment(int segmentIndex) {
        return -1.0 + 2.0 * segmentIndex / (ARC_SEGMENTS - 1);
    }

    private static ProbabilitySamples sampleArcProbabilities(GuessFactorDistribution distribution,
                                                             double gfStartOffset,
                                                             double gfEndOffset) {
        double[] hitProbs = new double[ARC_SEGMENTS];
        double maxHitProb = 0.0;
        for (int i = 0; i < ARC_SEGMENTS; i++) {
            double gf = gfForSegment(i);
            hitProbs[i] = distribution.integrate(gf + gfStartOffset, gf + gfEndOffset);
            if (hitProbs[i] > maxHitProb) {
                maxHitProb = hitProbs[i];
            }
        }
        return new ProbabilitySamples(hitProbs, maxHitProb);
    }

    private static void drawWaveArcSegments(Graphics2D graphics,
                                            Wave wave,
                                            double radius,
                                            double referenceBearing,
                                            double mea,
                                            double preciseMinGf,
                                            double preciseMaxGf,
                                            List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals,
                                            double[] hitProbs,
                                            double maxHitProb) {
        double prevX = Double.NaN;
        double prevY = Double.NaN;
        double prevGf = Double.NaN;
        for (int i = 0; i < ARC_SEGMENTS; i++) {
            double gf = gfForSegment(i);
            double angle = MathUtils.gfToAngle(referenceBearing, gf, mea);
            double px = wave.originX + radius * FastTrig.sin(angle);
            double py = wave.originY + radius * FastTrig.cos(angle);

            if (!Double.isNaN(prevX)) {
                if (isGfSegmentOutsideRange(prevGf, gf, preciseMinGf, preciseMaxGf)) {
                    graphics.setColor(OUTSIDE_PRECISE_MEA_COLOR);
                } else {
                    double shadowWeight = shadowWeightForSegment(prevGf, gf, activeShadowIntervals);
                    if (shadowWeight > 0.0) {
                        graphics.setColor(colorForShadowWeight(shadowWeight));
                    } else {
                        graphics.setColor(dangerToColor(hitProbs[i], maxHitProb));
                    }
                }
                graphics.drawLine((int) prevX, (int) prevY, (int) px, (int) py);
            }

            prevX = px;
            prevY = py;
            prevGf = gf;
        }
    }

    private static boolean isGfSegmentOutsideRange(double gfStart,
                                                   double gfEnd,
                                                   double allowedMinGf,
                                                   double allowedMaxGf) {
        double start = Math.min(gfStart, gfEnd);
        double end = Math.max(gfStart, gfEnd);
        return end < allowedMinGf || start > allowedMaxGf;
    }

    private static void drawPathPolyline(Graphics2D graphics,
                                         PhysicsUtil.Trajectory trajectory,
                                         int firstTick,
                                         int lastTick,
                                         Color color) {
        graphics.setColor(color);
        int clampedFirstTick = Math.max(0, Math.min(firstTick, trajectory.length() - 1));
        int clampedLastTick = Math.max(clampedFirstTick, Math.min(lastTick, trajectory.length() - 1));
        for (int i = clampedFirstTick; i < clampedLastTick; i++) {
            PhysicsUtil.PositionState s0 = trajectory.stateAt(i);
            PhysicsUtil.PositionState s1 = trajectory.stateAt(i + 1);
            graphics.drawLine(
                    (int) Math.round(s0.x),
                    (int) Math.round(s0.y),
                    (int) Math.round(s1.x),
                    (int) Math.round(s1.y));
        }
    }

    private static void drawPathNodes(Graphics2D graphics,
                                      PhysicsUtil.Trajectory trajectory,
                                      int firstTick,
                                      int lastTick,
                                      PathIntersectionLookup intersectionLookup,
                                      Color defaultNodeColor) {
        int clampedFirstTick = Math.max(0, Math.min(firstTick, trajectory.length() - 1));
        int clampedLastTick = Math.max(clampedFirstTick, Math.min(lastTick, trajectory.length() - 1));
        int radius = PATH_NODE_DIAMETER / 2;
        for (int i = clampedFirstTick; i <= clampedLastTick; i++) {
            PhysicsUtil.PositionState state = trajectory.stateAt(i);
            int intersectionIndex = intersectionLookup.intersectionIndexAt(i);
            if (intersectionIndex >= 0) {
                graphics.setColor(INTERSECTION_TICK_COLORS[intersectionIndex % INTERSECTION_TICK_COLORS.length]);
            } else {
                graphics.setColor(defaultNodeColor);
            }
            graphics.fillOval(
                    (int) Math.round(state.x) - radius,
                    (int) Math.round(state.y) - radius,
                    PATH_NODE_DIAMETER,
                    PATH_NODE_DIAMETER);
        }
    }

    private static void drawPath(Graphics2D graphics,
                                 CandidatePath path,
                                 List<PathWaveIntersection> intersections,
                                 Color lineColor,
                                 Color nodeColor) {
        drawPathSegment(
                graphics,
                path.trajectory,
                0,
                displayLastTick(path),
                buildPathIntersectionLookup(path, intersections),
                lineColor,
                nodeColor);
    }

    private static void drawPathSegment(Graphics2D graphics,
                                        PhysicsUtil.Trajectory trajectory,
                                        int firstTick,
                                        int lastTick,
                                        PathIntersectionLookup intersectionLookup,
                                        Color lineColor,
                                        Color nodeColor) {
        drawPathPolyline(graphics, trajectory, firstTick, lastTick, lineColor);
        drawPathNodes(graphics, trajectory, firstTick, lastTick, intersectionLookup, nodeColor);
    }

    private static int displayLastTick(CandidatePath path) {
        int maxTick = path.trajectory.length() - 1;
        if (path.firstWaveSafeSpotTick >= 0) {
            return Math.min(path.firstWaveSafeSpotTick, maxTick);
        }
        return maxTick;
    }

    private static Color styleForPath(CandidatePath path, boolean selected) {
        Color base = strategyColor(path.firstWaveStrategy);
        if (selected) {
            return scaleColor(base, 1.25, 255);
        }
        return scaleColor(base, 0.62, 150);
    }

    private static Color nodeStyleForPath(CandidatePath path, boolean selected) {
        Color base = strategyColor(path.firstWaveStrategy);
        if (selected) {
            return scaleColor(base, 1.15, 255);
        }
        return scaleColor(base, 0.72, 165);
    }

    private static Color strategyColor(WaveStrategy strategy) {
        switch (strategy) {
            case MAX_CW:
                return STRATEGY_MAX_CW_COLOR;
            case MAX_CCW:
                return STRATEGY_MAX_CCW_COLOR;
            case STRAIGHT:
                return STRATEGY_STRAIGHT_COLOR;
            case MOVE_IN:
                return STRATEGY_MOVE_IN_COLOR;
            case MOVE_OUT:
                return STRATEGY_MOVE_OUT_COLOR;
            default:
                return STRATEGY_UNKNOWN_COLOR;
        }
    }

    private static Color scaleColor(Color base, double factor, int alpha) {
        int red = clampColor((int) Math.round(base.getRed() * factor));
        int green = clampColor((int) Math.round(base.getGreen() * factor));
        int blue = clampColor((int) Math.round(base.getBlue() * factor));
        int clampedAlpha = clampColor(alpha);
        return new Color(red, green, blue, clampedAlpha);
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static void drawPrimaryTargetTick(Graphics2D graphics, long time, CandidatePath selectedPath) {
        if (selectedPath.firstWave == null
                || Double.isNaN(selectedPath.firstWaveSafeSpotX)
                || Double.isNaN(selectedPath.firstWaveSafeSpotY)) {
            return;
        }

        Wave wave = selectedPath.firstWave;
        double angle = Double.isNaN(selectedPath.firstWaveTargetAngle)
                ? Math.atan2(
                        selectedPath.firstWaveSafeSpotX - wave.originX,
                        selectedPath.firstWaveSafeSpotY - wave.originY)
                : selectedPath.firstWaveTargetAngle;
        double markerX = selectedPath.firstWaveSafeSpotX;
        double markerY = selectedPath.firstWaveSafeSpotY;

        graphics.setColor(Color.WHITE);
        graphics.setStroke(new BasicStroke(2));
        double radialX = FastTrig.sin(angle);
        double radialY = FastTrig.cos(angle);
        int tickHalfLength = 5;
        int x1 = (int) Math.round(markerX - radialX * tickHalfLength);
        int y1 = (int) Math.round(markerY - radialY * tickHalfLength);
        int x2 = (int) Math.round(markerX + radialX * tickHalfLength);
        int y2 = (int) Math.round(markerY + radialY * tickHalfLength);
        graphics.drawLine(x1, y1, x2, y2);
    }

    private static PathIntersectionLookup buildPathIntersectionLookup(
            CandidatePath selectedPath, List<PathWaveIntersection> intersections) {
        int trajectoryLength = selectedPath.trajectory.length();
        int[] firstIntersectionIndexByTick = new int[trajectoryLength];
        Arrays.fill(firstIntersectionIndexByTick, -1);
        if (intersections == null || intersections.isEmpty()) {
            return new PathIntersectionLookup(firstIntersectionIndexByTick);
        }

        long startTime = selectedPath.startTime;
        int eligibleIntersectionCount = 0;
        for (int tickOffset = 0; tickOffset < trajectoryLength; tickOffset++) {
            PhysicsUtil.PositionState state = selectedPath.trajectory.stateAt(tickOffset);
            long time = startTime + tickOffset;
            while (eligibleIntersectionCount < intersections.size()
                    && intersections.get(eligibleIntersectionCount).firstContactTime <= time) {
                eligibleIntersectionCount++;
            }
            firstIntersectionIndexByTick[tickOffset] =
                    firstIntersectingWaveIndex(intersections, eligibleIntersectionCount, time, state);
        }

        return new PathIntersectionLookup(firstIntersectionIndexByTick);
    }

    private static int firstIntersectingWaveIndex(List<PathWaveIntersection> intersections,
                                                  int eligibleIntersectionCount,
                                                  long time,
                                                  PhysicsUtil.PositionState state) {
        for (int i = 0; i < eligibleIntersectionCount; i++) {
            PathWaveIntersection intersection = intersections.get(i);
            double radius = intersection.wave.getRadius(time);
            if (intersection.wave.intersectsBodyAtRadius(state.x, state.y, radius)) {
                return i;
            }
        }

        return -1;
    }

    private static Color dangerToColor(double value, double maxValue) {
        if (maxValue <= 0) {
            return Color.GREEN;
        }
        double ratio = value / maxValue;
        ratio = Math.max(0, Math.min(1, ratio));

        int red;
        int green;
        if (ratio < 0.5) {
            red = (int) (255 * ratio * 2);
            green = 255;
        } else {
            red = 255;
            green = (int) (255 * (1 - ratio) * 2);
        }
        return new Color(red, green, 0);
    }

    private static Color colorForShadowWeight(double shadowWeight) {
        double clampedWeight = Math.max(0.0, Math.min(1.0, shadowWeight));
        if (clampedWeight <= 0.5) {
            double ratio = clampedWeight / 0.5;
            return blendColor(Color.WHITE, HALF_SHADOW_COLOR, ratio);
        }
        double ratio = (clampedWeight - 0.5) / 0.5;
        return blendColor(HALF_SHADOW_COLOR, FULL_SHADOW_COLOR, ratio);
    }

    private static Color blendColor(Color start, Color end, double ratio) {
        double clampedRatio = Math.max(0.0, Math.min(1.0, ratio));
        int red = (int) Math.round(start.getRed() + (end.getRed() - start.getRed()) * clampedRatio);
        int green = (int) Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * clampedRatio);
        int blue = (int) Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * clampedRatio);
        return new Color(red, green, blue);
    }

    private static List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals(
            Wave wave,
            double referenceBearing,
            double mea) {
        List<ShadowInterval> shadows = wave.getShadowIntervals();
        return BulletShadowUtil.mergeAndClipWeightedIntervals(
                BulletShadowUtil.allGfIntervals(shadows, referenceBearing, mea), -1.0, 1.0);
    }

    private static GuessFactorDistribution distributionForRender(Wave wave) {
        return wave.fireTimeDistributionHandle != null
                ? wave.fireTimeDistributionHandle.query()
                : DEFAULT_RENDER_DISTRIBUTION;
    }

    private static boolean hasWaveReference(Wave wave) {
        return !Double.isNaN(wave.targetX) && !Double.isNaN(wave.targetY);
    }
}


