package oog.mega.saguaro.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.Collections;
import java.util.List;

import oog.mega.saguaro.info.wave.BulletShadowUtil;
import oog.mega.saguaro.info.wave.BulletShadowUtil.ShadowInterval;
import oog.mega.saguaro.info.wave.Wave;
import oog.mega.saguaro.math.DefaultDistribution;
import oog.mega.saguaro.math.FastTrig;
import oog.mega.saguaro.math.GuessFactorDistribution;
import oog.mega.saguaro.math.MathUtils;

public final class WaveRenderer {
    private static final int ARC_SEGMENTS = 160;
    private static final int EXPERT_TICK_HALF_LENGTH = 4;
    private static final Color OUTSIDE_PRECISE_MEA_COLOR = new Color(120, 120, 120);
    private static final Color FULL_SHADOW_COLOR = Color.BLACK;
    private static final Color HALF_SHADOW_COLOR = Color.GRAY;
    private static final Color EXPERT_TICK_OUTLINE_COLOR = Color.BLACK;
    private static final Color EXPERT_TICK_INNER_COLOR = Color.WHITE;
    private static final List<BulletShadowUtil.WeightedGfInterval> NO_SHADOW_INTERVALS = Collections.emptyList();
    // Debug rendering still needs a visible density band before learning data exists.
    private static final GuessFactorDistribution DEFAULT_RENDER_DISTRIBUTION = new DefaultDistribution();

    private static final class ProbabilitySamples {
        private final double[] values;
        private final double max;

        private ProbabilitySamples(double[] values, double max) {
            this.values = values;
            this.max = max;
        }
    }

    public void render(Graphics2D graphics,
                       long time,
                       double battlefieldWidth,
                       double battlefieldHeight,
                       List<Wave> enemyWaves,
                       List<Wave> myWaves) {
        if (graphics == null) {
            return;
        }
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
            double[] preciseGfRange = MathUtils.inFieldMaxEscapeGfRange(
                    wave.originX, wave.originY, refX, refY, wave.speed, battlefieldWidth, battlefieldHeight);
            List<BulletShadowUtil.WeightedGfInterval> activeShadowIntervals = renderPersistentShadows
                    ? activeShadowIntervals(wave, referenceBearing, mea)
                    : NO_SHADOW_INTERVALS;
            GuessFactorDistribution distribution = distributionForRender(wave);
            ProbabilitySamples samples = sampleArcDensities(distribution);
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
            drawExpertTicks(graphics, wave, radius, referenceBearing, mea);
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

    private static double gfForSegment(int segmentIndex) {
        return -1.0 + 2.0 * segmentIndex / (ARC_SEGMENTS - 1);
    }

    private static ProbabilitySamples sampleArcDensities(GuessFactorDistribution distribution) {
        double[] densities = new double[ARC_SEGMENTS];
        double maxDensity = 0.0;
        for (int i = 0; i < ARC_SEGMENTS; i++) {
            double gf = gfForSegment(i);
            densities[i] = distribution.density(gf);
            if (densities[i] > maxDensity) {
                maxDensity = densities[i];
            }
        }
        return new ProbabilitySamples(densities, maxDensity);
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

    private static void drawExpertTicks(Graphics2D graphics,
                                        Wave wave,
                                        double radius,
                                        double referenceBearing,
                                        double mea) {
        if (wave.fireTimeRenderGfMarkers == null || wave.fireTimeRenderGfMarkers.length == 0) {
            return;
        }
        Stroke oldStroke = graphics.getStroke();
        for (double gf : wave.fireTimeRenderGfMarkers) {
            double angle = MathUtils.gfToAngle(referenceBearing, gf, mea);
            double radialX = FastTrig.sin(angle);
            double radialY = FastTrig.cos(angle);
            double x = wave.originX + radius * radialX;
            double y = wave.originY + radius * radialY;
            int x1 = (int) Math.round(x - radialX * EXPERT_TICK_HALF_LENGTH);
            int y1 = (int) Math.round(y - radialY * EXPERT_TICK_HALF_LENGTH);
            int x2 = (int) Math.round(x + radialX * EXPERT_TICK_HALF_LENGTH);
            int y2 = (int) Math.round(y + radialY * EXPERT_TICK_HALF_LENGTH);
            graphics.setColor(EXPERT_TICK_OUTLINE_COLOR);
            graphics.setStroke(new BasicStroke(3f));
            graphics.drawLine(x1, y1, x2, y2);
            graphics.setColor(EXPERT_TICK_INNER_COLOR);
            graphics.setStroke(new BasicStroke(1f));
            graphics.drawLine(x1, y1, x2, y2);
        }
        graphics.setStroke(oldStroke);
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
                ? wave.fireTimeDistributionHandle.exact()
                : DEFAULT_RENDER_DISTRIBUTION;
    }

    private static boolean hasWaveReference(Wave wave) {
        return !Double.isNaN(wave.targetX) && !Double.isNaN(wave.targetY);
    }
}
