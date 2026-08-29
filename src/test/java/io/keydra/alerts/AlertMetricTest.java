package io.keydra.alerts;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.alerts.entity.AlertMetric;
import io.keydra.engine.MetricsSample;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * What a metric reads, and — more importantly — when it reads nothing.
 *
 * <p>The absent answers are the whole point of these tests. A metric that returned zero when it
 * could not be read would clear a real alert or raise a false one depending only on which way
 * somebody happened to write the rule, and neither failure announces itself.
 */
class AlertMetricTest {

    private static final Instant NOON = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void memoryIsReadStraightOffTheSample() {
        MetricsSample sample = sample(NOON, 500L, 1000L, 12L);

        assertThat(AlertMetric.MEMORY_USED_BYTES.of(sample, null), is(500.0));
    }

    @Test
    void fillIsAPercentageOfTheCeiling() {
        MetricsSample sample = sample(NOON, 750L, 1000L, 12L);

        assertThat(AlertMetric.MEMORY_FILL_PERCENT.of(sample, null), is(75.0));
    }

    @Test
    void aTargetWithNoCeilingHasNoFillPercentage() {
        MetricsSample sample = sample(NOON, 750L, null, 12L);

        // Not zero, and not a hundred. "Full" means nothing without a ceiling, and a number
        // here would be a rule firing or staying quiet on a fact nobody established.
        assertThat(AlertMetric.MEMORY_FILL_PERCENT.of(sample, null), is(nullValue()));
    }

    @Test
    void aRateNeedsTwoReadings() {
        MetricsSample first = withEvictions(NOON, 100L);

        assertThat(AlertMetric.EVICTED_KEYS_PER_MINUTE.of(first, null), is(nullValue()));
    }

    @Test
    void aRateIsTheDifferenceOverTheGap() {
        MetricsSample before = withEvictions(NOON, 100L);
        MetricsSample now = withEvictions(NOON.plusSeconds(30), 160L);

        // Sixty more in half a minute is a hundred and twenty a minute.
        assertThat(AlertMetric.EVICTED_KEYS_PER_MINUTE.of(now, before), closeTo(120.0, 0.001));
    }

    @Test
    void aCounterThatWentBackwardsIsNotARate() {
        MetricsSample before = withEvictions(NOON, 5000L);
        MetricsSample now = withEvictions(NOON.plusSeconds(30), 3L);

        // What a restart looks like from here. The arithmetic would give a large negative
        // rate, which a rule written "below" would report as an eviction storm.
        assertThat(AlertMetric.EVICTED_KEYS_PER_MINUTE.of(now, before), is(nullValue()));
    }

    @Test
    void silenceIsNotAReadingForAnythingButTheMetricAboutSilence() {
        assertThat(AlertMetric.MEMORY_USED_BYTES.of(null, null), is(nullValue()));
        assertThat(AlertMetric.CONNECTED_CLIENTS.of(null, null), is(nullValue()));
        assertThat(AlertMetric.NO_ANSWER.of(null, null), is(1.0));
    }

    @Test
    void aTargetThatAnsweredIsAnsweringVerbatim() {
        assertThat(AlertMetric.NO_ANSWER.of(sample(NOON, 1L, 2L, 3L), null), is(0.0));
    }

    @Test
    void aHitRatioNobodyHasLookedUpIsAbsentRatherThanZero() {
        MetricsSample idle =
                new MetricsSample(NOON, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L, 0L, 60L, 0L, 0L);

        assertThat(AlertMetric.HIT_RATIO_PERCENT.of(idle, null), is(nullValue()));
    }

    private static MetricsSample sample(Instant at, Long used, Long max, Long clients) {
        return new MetricsSample(at, used, used, max, clients, 5L, 100L, 8L, 2L, 42L, 900L, 0L, 0L);
    }

    private static MetricsSample withEvictions(Instant at, Long evicted) {
        return new MetricsSample(at, 1L, 1L, 2L, 1L, 5L, 100L, 8L, 2L, 42L, 900L, evicted, 0L);
    }
}
