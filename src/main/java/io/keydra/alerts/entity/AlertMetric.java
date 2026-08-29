package io.keydra.alerts.entity;

import io.keydra.engine.MetricsSample;
import java.time.Duration;

/**
 * A number a rule can be written about.
 *
 * <p>A closed list rather than a path into {@code INFO}, for the same reason {@link
 * io.keydra.authz.entity.Permission} is a closed list: a rule about a field nobody has heard of is
 * a rule nobody can review, and a store that reports a different hundred fields would make every
 * rule unportable. These are the readings phase 5 already takes, which is also why watching costs
 * nothing new — no second poller, nothing extra asked of the target.
 *
 * <p>Every reading may be absent, and absent is not zero. A target that reports no memory ceiling
 * has no fill percentage; a target nobody has asked twice has no rate yet. A rule whose metric
 * cannot be read this time is left exactly as it was rather than being told the answer is zero,
 * which would clear a real alert or raise a false one depending on which way it was written.
 */
public enum AlertMetric {

    /** Memory the store is holding. */
    MEMORY_USED_BYTES(Unit.BYTES) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return asDouble(now.memoryUsedBytes());
        }
    },

    /**
     * How full it is against the ceiling it was given.
     *
     * <p>The question people actually mean by "is it running out": a gigabyte is fine on one server
     * and the end on another, and only the ceiling says which. Absent on a target configured
     * without one, where "full" has no meaning to compare against.
     */
    MEMORY_FILL_PERCENT(Unit.PERCENT) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            Long max = now.memoryMaxBytes();
            Long used = now.memoryUsedBytes();
            return max == null || max == 0 || used == null ? null : (used * 100.0) / max;
        }
    },

    CONNECTED_CLIENTS(Unit.COUNT) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return asDouble(now.connectedClients());
        }
    },

    OPS_PER_SECOND(Unit.PER_SECOND) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return asDouble(now.opsPerSecond());
        }
    },

    /** Hits as a percentage of lookups; absent while nothing has been looked up. */
    HIT_RATIO_PERCENT(Unit.PERCENT) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            Double ratio = now.hitRatio();
            return ratio == null ? null : ratio * 100;
        }
    },

    KEY_COUNT(Unit.COUNT) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return asDouble(now.keyCount());
        }
    },

    /** Keys dropped to stay under the ceiling, as a rate rather than a total. */
    EVICTED_KEYS_PER_MINUTE(Unit.PER_MINUTE) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return perMinute(
                    now.evictedKeys(), before == null ? null : before.evictedKeys(), now, before);
        }
    },

    EXPIRED_KEYS_PER_MINUTE(Unit.PER_MINUTE) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return perMinute(
                    now.expiredKeys(), before == null ? null : before.expiredKeys(), now, before);
        }
    },

    /**
     * How long it has been up, which is how a restart is noticed.
     *
     * <p>Written as "below sixty seconds": a server that has just started is a server that has just
     * stopped, and nothing else in a dashboard says so after the fact.
     */
    UPTIME_SECONDS(Unit.SECONDS) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            return asDouble(now.uptimeSeconds());
        }
    },

    /**
     * Nothing came back.
     *
     * <p>The one condition that is the absence of a reading rather than a reading, and the one most
     * worth being told about. A target that has stopped answering reports no memory figure to
     * compare against a threshold, so a model where every rule needs a number would be a model that
     * goes quiet exactly when the server does.
     */
    NO_ANSWER(Unit.CONDITION) {
        @Override
        Double read(MetricsSample now, MetricsSample before) {
            // There was an answer, which is this metric's way of saying nothing is wrong.
            return 0.0;
        }

        @Override
        Double whenSilent() {
            return 1.0;
        }
    };

    /** What the number is, so an interface can write it out and a form can suggest a threshold. */
    public enum Unit {
        BYTES,
        PERCENT,
        COUNT,
        PER_SECOND,
        PER_MINUTE,
        SECONDS,
        /** True or false rather than a quantity; the threshold is not a question worth asking. */
        CONDITION
    }

    private final Unit unit;

    AlertMetric(Unit unit) {
        this.unit = unit;
    }

    public Unit unit() {
        return unit;
    }

    /** Whether this is a yes-or-no condition, which decides whether a threshold means anything. */
    public boolean isCondition() {
        return unit == Unit.CONDITION;
    }

    /**
     * Whether reading this needs the reading before it as well.
     *
     * <p>Which is what decides whether it can have a baseline: a rate measured between two readings
     * has no meaning in a single figure aggregated out of an hour of them, so a rule written that
     * way would never fire and would never say why. Refused while it is being written instead.
     */
    public boolean needsTwoReadings() {
        return this == EVICTED_KEYS_PER_MINUTE || this == EXPIRED_KEYS_PER_MINUTE;
    }

    /**
     * The reading, or null when this target cannot answer it.
     *
     * <p>A missing sample means the target said nothing at all, which every metric but one has no
     * answer for — {@link #NO_ANSWER} overrides this, because that silence is its answer.
     */
    public Double of(MetricsSample now, MetricsSample before) {
        return now == null ? whenSilent() : read(now, before);
    }

    abstract Double read(MetricsSample now, MetricsSample before);

    /**
     * What this metric reads when the target said nothing at all.
     *
     * <p>Null for every quantity, because the absence of a reading is not a value; overridden by
     * the one metric whose subject is that silence.
     */
    Double whenSilent() {
        return null;
    }

    private static Double asDouble(Long value) {
        return value == null ? null : value.doubleValue();
    }

    /**
     * A counter turned into a rate, using the gap between the two readings.
     *
     * <p>Null when the counter went backwards, which is what a restart looks like from here. The
     * arithmetic would produce a large negative rate and a rule written "above" would simply miss
     * it — but a rule written "below" would fire on a restart, saying the wrong thing about the
     * right event. Not knowing is the honest answer for one interval.
     */
    private static Double perMinute(Long now, Long before, MetricsSample at, MetricsSample last) {
        if (now == null || before == null || last == null || now < before) {
            return null;
        }
        Duration gap = Duration.between(last.at(), at.at());
        double minutes = gap.toMillis() / 60_000.0;
        return minutes <= 0 ? null : (now - before) / minutes;
    }
}
