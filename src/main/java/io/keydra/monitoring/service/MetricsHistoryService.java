package io.keydra.monitoring.service;

import io.keydra.engine.MetricsSample;
import io.keydra.monitoring.dto.MetricsHistory;
import io.keydra.monitoring.sink.MetricsSink;
import io.keydra.monitoring.sink.RingSink;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Answers a window of readings from whichever sink can.
 *
 * <p>Memory first when the window fits inside it: what is held there is every reading taken and is
 * fresher than a store by a flush interval, and most windows anybody asks for are the last few
 * minutes. Anything older goes to a durable sink, and if there is none the answer says so rather
 * than drawing an hour and calling it a month.
 */
@ApplicationScoped
public class MetricsHistoryService {

    private final RingSink ring;
    private final List<MetricsSink> sinks;
    private final Duration interval;

    @Inject
    MetricsHistoryService(
            RingSink ring,
            Instance<MetricsSink> sinks,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                            name = "keydra.monitoring.interval",
                            defaultValue = "5s")
                    Duration interval) {
        this.ring = ring;
        this.sinks = sinks.stream().toList();
        this.interval = interval;
    }

    /** Whether anything here outlives the process, which decides how far back a window can go. */
    public boolean durable() {
        return sinks.stream().anyMatch(MetricsSink::isDurable);
    }

    /**
     * How far back memory alone can answer.
     *
     * <p>The sampling interval times how many readings are kept — about an hour by default. Asked
     * before a rule is saved against a window, so "compare with last week" on an instance with no
     * store is refused while somebody is looking at it rather than silently never firing.
     */
    public Duration memoryReach() {
        return interval.multipliedBy(ring.retained());
    }

    public Uni<MetricsHistory> between(Long connectionId, Instant from, Instant to, int points) {
        Instant earliest = ring.earliest(connectionId);
        boolean fitsInMemory = earliest != null && !earliest.isAfter(from);
        if (fitsInMemory) {
            return ring.between(connectionId, from, to, points)
                    .map(
                            samples ->
                                    new MetricsHistory(
                                            MetricsHistory.Source.MEMORY,
                                            (int) interval.toSeconds(),
                                            samples));
        }

        MetricsSink durable =
                sinks.stream().filter(MetricsSink::isDurable).findFirst().orElse(null);
        if (durable == null) {
            // Whatever memory has, and an honest label: the window asked for is older than
            // anything this instance kept, and pretending otherwise is how a chart lies.
            return ring.between(connectionId, from, to, points)
                    .map(
                            samples ->
                                    new MetricsHistory(
                                            samples.isEmpty()
                                                    ? MetricsHistory.Source.NONE
                                                    : MetricsHistory.Source.MEMORY,
                                            (int) interval.toSeconds(),
                                            samples));
        }

        long seconds = Math.max(1, Duration.between(from, to).toSeconds());
        int step = (int) Math.max(interval.toSeconds(), seconds / Math.max(1, points));
        return durable.between(connectionId, from, to, points)
                .map(samples -> new MetricsHistory(MetricsHistory.Source.STORE, step, samples))
                .onFailure()
                .recoverWithItem(
                        () ->
                                new MetricsHistory(
                                        MetricsHistory.Source.NONE,
                                        step,
                                        List.<MetricsSample>of()));
    }
}
