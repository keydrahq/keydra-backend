package io.keydra.monitoring;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import io.keydra.AbstractTestBase;
import io.keydra.engine.MetricsSample;
import io.keydra.monitoring.dto.MetricsHistory;
import io.keydra.monitoring.service.MetricsHistoryService;
import io.keydra.monitoring.sink.ClickHouseSink;
import io.keydra.resources.ClickHouseResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Readings that outlive the process.
 *
 * <p>Against a real ClickHouse, because everything that could be wrong here is a string this
 * application composes — the schema, the insert format, the bucketing query — and a stub would
 * accept all of them, including the wrong ones.
 */
@QuarkusTest
@WithTestResource(ClickHouseResource.class)
class ClickHouseSinkTest extends AbstractTestBase {

    /**
     * A target nobody has written before, per test.
     *
     * <p>The container is reused between runs — starting one is the slowest thing this suite does —
     * so a fixed id would mean each run reading the previous run's rows and counting them. Time
     * makes an id that has not been used, which is all these tests need it to be.
     */
    private final long target = System.nanoTime();

    @Inject ClickHouseSink sink;
    @Inject MetricsHistoryService history;

    @Test
    void writesReadingsAndReadsThemBackInBuckets() {
        Instant start = Instant.now().minus(Duration.ofMinutes(30));
        // Half an hour of readings a minute apart, with memory climbing steadily.
        for (int minute = 0; minute < 30; minute++) {
            sink.write(target, reading(start.plus(Duration.ofMinutes(minute)), 100L + minute));
        }

        // The window is far older than anything memory holds, so this is the store answering.
        MetricsHistory page =
                Awaitility.await()
                        .atMost(Duration.ofSeconds(30))
                        .until(
                                () ->
                                        history.between(
                                                        target,
                                                        start.minus(Duration.ofMinutes(1)),
                                                        Instant.now(),
                                                        10)
                                                .await()
                                                .indefinitely(),
                                answer -> !answer.samples().isEmpty());

        assertThat(page.source(), is(MetricsHistory.Source.STORE));
        // Ten points asked for over half an hour: buckets of a few minutes, not thirty rows.
        assertThat(page.samples().size(), lessThanOrEqualTo(12));
        assertThat(page.samples().size(), greaterThanOrEqualTo(1));

        // Averaged within each bucket and ordered oldest first, which is what a chart plots.
        List<MetricsSample> samples = page.samples();
        assertThat(
                samples.get(0).at().isBefore(samples.get(samples.size() - 1).at()),
                is(samples.size() > 1));
        double first = samples.get(0).memoryUsedBytes();
        double last = samples.get(samples.size() - 1).memoryUsedBytes();
        assertThat(last - first, closeTo(29.0, 6.0));
    }

    @Test
    void answersNothingForATargetItHasNeverSeen() {
        MetricsHistory page =
                history.between(
                                987_654L,
                                Instant.now().minus(Duration.ofDays(2)),
                                Instant.now(),
                                10)
                        .await()
                        .indefinitely();

        // An empty answer rather than a shorter window drawn as if it were the one asked for.
        assertThat(page.samples(), is(empty()));
    }

    @Test
    void keepsWhatMemoryCannot() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(3));
        sink.write(target + 1, reading(longAgo, 500L));

        MetricsHistory page =
                Awaitility.await()
                        .atMost(Duration.ofSeconds(30))
                        .until(
                                () ->
                                        history.between(
                                                        target + 1,
                                                        longAgo.minus(Duration.ofHours(1)),
                                                        Instant.now(),
                                                        50)
                                                .await()
                                                .indefinitely(),
                                answer -> !answer.samples().isEmpty());

        // Three days old, which no ring buffer in this application has ever held.
        assertThat(page.source(), is(MetricsHistory.Source.STORE));
        assertThat(page.samples(), hasSize(1));
        assertThat(page.samples().get(0).memoryUsedBytes(), is(500L));
    }

    private static MetricsSample reading(Instant at, long memory) {
        return new MetricsSample(
                at, memory, memory, 1000L, 3L, 7L, 100L, 8L, 2L, 42L, 900L, 0L, 0L);
    }
}
