package oog.mega.saguaro.render;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.movement.CandidatePath;
import oog.mega.saguaro.movement.PathWaveIntersection;
import oog.mega.saguaro.math.PhysicsUtil;

public final class PathOverlay {
    public static final class Marker {
        public enum Style {
            LINE,
            CROSSHAIR,
            BOX
        }

        public final double x;
        public final double y;
        public final double angle;
        public final Color color;
        public final float strokeWidth;
        public final int halfLength;
        public final Style style;

        public Marker(double x,
                      double y,
                      double angle,
                      Color color,
                      float strokeWidth,
                      int halfLength) {
            this(x, y, angle, color, strokeWidth, halfLength, Style.LINE);
        }

        public Marker(double x,
                      double y,
                      double angle,
                      Color color,
                      float strokeWidth,
                      int halfLength,
                      Style style) {
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.color = color;
            this.strokeWidth = strokeWidth;
            this.halfLength = halfLength;
            this.style = style != null ? style : Style.LINE;
        }
    }

    public final PhysicsUtil.Trajectory trajectory;
    public final long startTime;
    public final List<PathWaveIntersection> intersections;
    public final Color lineColor;
    public final Color nodeColor;
    public final float strokeWidth;
    public final boolean renderNodes;
    public final Marker marker;

    public PathOverlay(PhysicsUtil.Trajectory trajectory,
                       long startTime,
                       List<PathWaveIntersection> intersections,
                       Color lineColor,
                       Color nodeColor,
                       float strokeWidth,
                       boolean renderNodes,
                       Marker marker) {
        this.trajectory = trajectory;
        this.startTime = startTime;
        this.intersections =
                intersections != null ? intersections : Collections.<PathWaveIntersection>emptyList();
        this.lineColor = lineColor;
        this.nodeColor = nodeColor != null ? nodeColor : lineColor;
        this.strokeWidth = strokeWidth;
        this.renderNodes = renderNodes;
        this.marker = marker;
    }

    public static PathOverlay forTrajectory(PhysicsUtil.Trajectory trajectory,
                                            Color lineColor,
                                            float strokeWidth) {
        return new PathOverlay(trajectory, 0L, null, lineColor, lineColor, strokeWidth, false, null);
    }

    public static PathOverlay forCandidatePath(CandidatePath path,
                                               List<PathWaveIntersection> intersections,
                                               Color lineColor,
                                               Color nodeColor,
                                               Color markerColor,
                                               float strokeWidth) {
        if (path == null) {
            return null;
        }
        return new PathOverlay(
                path.trajectory,
                path.startTime,
                intersections,
                lineColor,
                nodeColor,
                strokeWidth,
                true,
                buildMarker(path, markerColor));
    }

    private static Marker buildMarker(CandidatePath path, Color markerColor) {
        if (path.firstWave == null
                || Double.isNaN(path.firstWaveSafeSpotX)
                || Double.isNaN(path.firstWaveSafeSpotY)) {
            return null;
        }
        double angle = Double.isNaN(path.firstWaveTargetAngle)
                ? Math.atan2(
                        path.firstWaveSafeSpotX - path.firstWave.originX,
                        path.firstWaveSafeSpotY - path.firstWave.originY)
                : path.firstWaveTargetAngle;
        return new Marker(
                path.firstWaveSafeSpotX,
                path.firstWaveSafeSpotY,
                angle,
                markerColor,
                2.0f,
                5);
    }
}
