package oog.mega.saguaro.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.Arrays;
import java.util.List;

import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.PhysicsUtil;
import oog.mega.saguaro.movement.PathWaveIntersection;

public final class PathRenderer {
    private static final Color INTERSECTION_PATH_COLOR = new Color(255, 86, 86);
    private static final Color INTERSECTION_NODE_COLOR = new Color(255, 86, 86, 230);
    private static final int PATH_NODE_DIAMETER = 5;

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
                       List<PathOverlay> pathOverlays) {
        if (graphics == null) {
            return;
        }
        if (pathOverlays == null || pathOverlays.isEmpty()) {
            return;
        }
        for (PathOverlay overlay : pathOverlays) {
            drawPathOverlay(graphics, overlay);
        }
    }

    private void drawPathOverlay(Graphics2D graphics, PathOverlay overlay) {
        if (overlay == null || overlay.trajectory == null || overlay.lineColor == null || overlay.trajectory.length() == 0) {
            return;
        }

        Stroke oldStroke = graphics.getStroke();
        graphics.setStroke(new BasicStroke(overlay.strokeWidth > 0.0f ? overlay.strokeWidth : 1.0f));
        PathIntersectionLookup intersectionLookup =
                buildPathIntersectionLookup(overlay.trajectory, overlay.startTime, overlay.intersections);
        int maxTick = overlay.trajectory.length() - 1;
        drawPathPolyline(graphics, overlay.trajectory, 0, maxTick, intersectionLookup, overlay.lineColor);
        if (overlay.renderNodes) {
            drawPathNodes(graphics, overlay.trajectory, 0, maxTick, intersectionLookup, overlay.nodeColor);
        }
        drawMarker(graphics, overlay.marker);
        graphics.setStroke(oldStroke);
    }

    private static void drawPathPolyline(Graphics2D graphics,
                                         PhysicsUtil.Trajectory trajectory,
                                         int firstTick,
                                         int lastTick,
                                         PathIntersectionLookup intersectionLookup,
                                         Color color) {
        int clampedFirstTick = Math.max(0, Math.min(firstTick, trajectory.length() - 1));
        int clampedLastTick = Math.max(clampedFirstTick, Math.min(lastTick, trajectory.length() - 1));
        for (int i = clampedFirstTick; i < clampedLastTick; i++) {
            PhysicsUtil.PositionState s0 = trajectory.stateAt(i);
            PhysicsUtil.PositionState s1 = trajectory.stateAt(i + 1);
            graphics.setColor(segmentColor(intersectionLookup, i, color));
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
                graphics.setColor(INTERSECTION_NODE_COLOR);
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

    private static Color segmentColor(PathIntersectionLookup intersectionLookup, int tick, Color defaultColor) {
        return intersectionLookup.intersectionIndexAt(tick) >= 0
                || intersectionLookup.intersectionIndexAt(tick + 1) >= 0
                ? INTERSECTION_PATH_COLOR
                : defaultColor;
    }

    private static void drawMarker(Graphics2D graphics, PathOverlay.Marker marker) {
        if (marker == null || marker.color == null) {
            return;
        }

        graphics.setColor(marker.color);
        graphics.setStroke(new BasicStroke(marker.strokeWidth > 0.0f ? marker.strokeWidth : 1.0f));
        if (marker.style == PathOverlay.Marker.Style.CROSSHAIR) {
            int x = (int) Math.round(marker.x);
            int y = (int) Math.round(marker.y);
            int halfLength = Math.max(1, marker.halfLength);
            graphics.drawLine(x - halfLength, y, x + halfLength, y);
            graphics.drawLine(x, y - halfLength, x, y + halfLength);
            return;
        }
        if (marker.style == PathOverlay.Marker.Style.BOX) {
            int halfLength = Math.max(1, marker.halfLength);
            int size = halfLength * 2;
            graphics.drawRect(
                    (int) Math.round(marker.x) - halfLength,
                    (int) Math.round(marker.y) - halfLength,
                    size,
                    size);
            return;
        }
        double radialX = FastTrig.sin(marker.angle);
        double radialY = FastTrig.cos(marker.angle);
        int x1 = (int) Math.round(marker.x - radialX * marker.halfLength);
        int y1 = (int) Math.round(marker.y - radialY * marker.halfLength);
        int x2 = (int) Math.round(marker.x + radialX * marker.halfLength);
        int y2 = (int) Math.round(marker.y + radialY * marker.halfLength);
        graphics.drawLine(x1, y1, x2, y2);
    }

    private static PathIntersectionLookup buildPathIntersectionLookup(
            PhysicsUtil.Trajectory trajectory, long startTime, List<PathWaveIntersection> intersections) {
        int trajectoryLength = trajectory.length();
        int[] firstIntersectionIndexByTick = new int[trajectoryLength];
        Arrays.fill(firstIntersectionIndexByTick, -1);
        if (intersections == null || intersections.isEmpty()) {
            return new PathIntersectionLookup(firstIntersectionIndexByTick);
        }

        int eligibleIntersectionCount = 0;
        for (int tickOffset = 0; tickOffset < trajectoryLength; tickOffset++) {
            PhysicsUtil.PositionState state = trajectory.stateAt(tickOffset);
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
}
