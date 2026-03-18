package oog.mega.saguaro.math;

/**
 * Class for fast/inexact approximations of math functions
 *
 * This implementation is based on Rednaxela's FastTrig, so thanks to him for making that available.
 */
public final class FastTrig {
    private static final int TRIG_DIVISIONS = 8192;
    private static final int ATAN_DIVISIONS = 131072;
    private static final int ACOS_DIVISIONS = 131072;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double HALF_PI = Math.PI * 0.5;
    private static final int SINE_MASK = TRIG_DIVISIONS - 1;
    private static final int COS_SHIFT = TRIG_DIVISIONS / 4;
    private static final double K = TRIG_DIVISIONS / TWO_PI;
    private static final double ATAN_MAX_VALUE = 40.0;
    private static final double ATAN_K = ATAN_DIVISIONS / ATAN_MAX_VALUE;
    private static final double ACOS_K = ACOS_DIVISIONS / 2.0;
    private static final double[] SINE_TABLE = new double[TRIG_DIVISIONS];
    private static final double[] ATAN_TABLE = new double[ATAN_DIVISIONS + 1];
    private static final double[] ACOS_TABLE = new double[ACOS_DIVISIONS + 1];

    static {
        for (int i = 0; i < TRIG_DIVISIONS; i++) {
            SINE_TABLE[i] = Math.sin(i / K);
        }
        for (int i = 0; i <= ATAN_DIVISIONS; i++) {
            ATAN_TABLE[i] = Math.atan(i / ATAN_K);
        }
        for (int i = 0; i <= ACOS_DIVISIONS; i++) {
            ACOS_TABLE[i] = Math.acos(i / ACOS_K - 1.0);
        }
    }

    private FastTrig() {
    }

    public static double sin(double value) {
        return SINE_TABLE[((int) (value * K + 0.5)) & SINE_MASK];
    }

    public static double cos(double value) {
        return SINE_TABLE[(((int) (value * K + 0.5)) + COS_SHIFT) & SINE_MASK];
    }

    public static double atan(double value) {
        if (value >= 0.0) {
            if (value < ATAN_MAX_VALUE) {
                return ATAN_TABLE[(int) (value * ATAN_K + 0.5)];
            }
            return HALF_PI - ATAN_TABLE[(int) (ATAN_K / value + 0.5)];
        }
        if (value > -ATAN_MAX_VALUE) {
            return -ATAN_TABLE[(int) (-value * ATAN_K + 0.5)];
        }
        return -HALF_PI + ATAN_TABLE[(int) (-ATAN_K / value + 0.5)];
    }

    /**
     * Fast atan2 matching {@link Math#atan2(double, double)} argument order.
     */
    public static double atan2(double y, double x) {
        if (x == 0.0) {
            if (y > 0.0) {
                return HALF_PI;
            }
            if (y < 0.0) {
                return -HALF_PI;
            }
            return 0.0;
        }

        double z = y / x;
        if (Math.abs(z) < 1.0) {
            double atan = atan(z);
            if (x < 0.0) {
                return y < 0.0 ? atan - Math.PI : atan + Math.PI;
            }
            return atan;
        }

        double atan = HALF_PI - atan(1.0 / z);
        if (y < 0.0) {
            return atan - Math.PI;
        }
        return atan;
    }

    public static double acos(double value) {
        if (value <= -1.0) {
            return Math.PI;
        }
        if (value >= 1.0) {
            return 0.0;
        }
        return ACOS_TABLE[(int) (value * ACOS_K + (ACOS_DIVISIONS * 0.5) + 0.5)];
    }

    public static double asin(double value) {
        return HALF_PI - acos(value);
    }
}

