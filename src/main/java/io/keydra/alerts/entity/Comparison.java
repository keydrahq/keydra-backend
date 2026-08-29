package io.keydra.alerts.entity;

/**
 * Which side of the threshold is the wrong side.
 *
 * <p>Two, not six. "At least" and "greater than" differ only when a reading lands exactly on the
 * threshold, and a rule that turns on that coincidence is a rule nobody meant to write.
 */
public enum Comparison {
    ABOVE {
        @Override
        public boolean holds(double value, double threshold) {
            return value > threshold;
        }
    },
    BELOW {
        @Override
        public boolean holds(double value, double threshold) {
            return value < threshold;
        }
    };

    public abstract boolean holds(double value, double threshold);
}
