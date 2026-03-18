package oog.mega.saguaro.movement;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum WaveStrategy {
    NONE,
    MAX_CW,
    MAX_CCW,
    MOVE_OUT,
    MOVE_IN,
    STRAIGHT;

    private static final List<WaveStrategy> APPROACH_VARIANTS = Collections.unmodifiableList(Arrays.asList(
            MAX_CW,
            MAX_CCW,
            MOVE_OUT,
            MOVE_IN,
            STRAIGHT));
    private static final List<WaveStrategy> STRAIGHT_ONLY = Collections.singletonList(STRAIGHT);

    public static List<WaveStrategy> primaryVariants(boolean includeApproachVariants) {
        return includeApproachVariants ? APPROACH_VARIANTS : STRAIGHT_ONLY;
    }

    public boolean isVelocityShaping() {
        return this == MAX_CW || this == MAX_CCW;
    }

    public int desiredTangentialVelocitySign() {
        if (this == MAX_CW) {
            return 1;
        }
        if (this == MAX_CCW) {
            return -1;
        }
        throw new IllegalStateException("Strategy does not define tangential velocity shaping: " + this);
    }

    public int radialCommandSign() {
        if (this == MOVE_OUT) {
            return 1;
        }
        if (this == MOVE_IN) {
            return -1;
        }
        throw new IllegalStateException("Strategy does not define radial movement: " + this);
    }
}
