package oog.mega.saguaro.info.state;

final class RecentMotionHistory {
    private static final int CAPACITY = 128;

    private final double[] velocities = new double[CAPACITY];
    private final double[] headingDeltas = new double[CAPACITY];
    private int start;
    private int size;

    void clear() {
        start = 0;
        size = 0;
    }

    void addSample(double velocity, double headingDelta) {
        int insertIndex;
        if (size < CAPACITY) {
            insertIndex = (start + size) % CAPACITY;
            size++;
        } else {
            insertIndex = start;
            start = (start + 1) % CAPACITY;
        }
        velocities[insertIndex] = velocity;
        headingDeltas[insertIndex] = headingDelta;
    }

    int size() {
        return size;
    }

    double velocityAt(int index) {
        return velocities[translateIndex(index)];
    }

    double headingDeltaAt(int index) {
        return headingDeltas[translateIndex(index)];
    }

    private int translateIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("Motion-history index out of range: " + index);
        }
        return (start + index) % CAPACITY;
    }
}
