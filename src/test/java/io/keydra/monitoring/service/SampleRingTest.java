package io.keydra.monitoring.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.engine.MetricsSample;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SampleRingTest {

    private static MetricsSample reading(long memory) {
        return new MetricsSample(
                Instant.EPOCH,
                memory,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static List<Long> memoriesIn(SampleRing ring) {
        return ring.toList().stream().map(MetricsSample::memoryUsedBytes).toList();
    }

    @Test
    void startsEmpty() {
        SampleRing ring = new SampleRing(3);

        assertThat(ring.toList(), empty());
        assertThat(ring.latest(), nullValue());
    }

    @Test
    void keepsReadingsOldestFirst() {
        SampleRing ring = new SampleRing(3);
        ring.add(reading(1));
        ring.add(reading(2));

        assertThat(memoriesIn(ring), contains(1L, 2L));
        assertThat(ring.latest().memoryUsedBytes(), equalTo(2L));
    }

    @Test
    void dropsTheOldestRatherThanRefusingTheNewest() {
        SampleRing ring = new SampleRing(3);
        for (long i = 1; i <= 5; i++) {
            ring.add(reading(i));
        }

        // A dashboard shows a window; the window must move.
        assertThat(memoriesIn(ring), contains(3L, 4L, 5L));
        assertThat(ring.size(), equalTo(3));
    }

    @Test
    void staysCorrectAfterWrappingSeveralTimes() {
        SampleRing ring = new SampleRing(3);
        for (long i = 1; i <= 100; i++) {
            ring.add(reading(i));
        }

        assertThat(memoriesIn(ring), contains(98L, 99L, 100L));
    }
}
