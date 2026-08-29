package io.keydra.monitoring.sink;

import io.keydra.connections.dto.ConnectionRemoved;
import io.keydra.engine.MetricsSample;
import io.keydra.monitoring.service.SampleRing;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The last hour, in memory.
 *
 * <p>The sink that was always here, now behind the interface. It is the default and stays the
 * default: it costs nothing, it cannot be misconfigured, and the question a dashboard is opened
 * with is almost always about the last few minutes.
 *
 * <p>Kept per target rather than per watch, which is a small change with a visible consequence:
 * closing a dashboard and opening it again no longer starts the chart from nothing.
 */
@ApplicationScoped
public class RingSink implements MetricsSink {

    private final int retained;
    private final Map<Long, SampleRing> rings = new ConcurrentHashMap<>();

    @Inject
    RingSink(
            @ConfigProperty(name = "keydra.monitoring.retained-samples", defaultValue = "720")
                    int retained) {
        this.retained = retained;
    }

    @Override
    public void write(Long connectionId, MetricsSample sample) {
        rings.computeIfAbsent(connectionId, ignored -> new SampleRing(retained)).add(sample);
    }

    @Override
    public boolean isDurable() {
        return false;
    }

    @Override
    public Uni<List<MetricsSample>> between(
            Long connectionId, Instant from, Instant to, int points) {
        return Uni.createFrom()
                .item(
                        recent(connectionId).stream()
                                .filter(
                                        sample ->
                                                !sample.at().isBefore(from)
                                                        && !sample.at().isAfter(to))
                                .toList());
    }

    /** How many readings are kept per target, which is how far back this can answer. */
    public int retained() {
        return retained;
    }

    /**
     * The oldest reading still held, or null when none is.
     *
     * <p>Asked so a range can be answered from here when it fits and from a store when it does not:
     * what is in memory is fresher by a flush interval, and most windows fit.
     */
    public Instant earliest(Long connectionId) {
        List<MetricsSample> held = recent(connectionId);
        return held.isEmpty() ? null : held.get(0).at();
    }

    /** Everything held for one target, oldest first — which is the order a chart plots. */
    public List<MetricsSample> recent(Long connectionId) {
        SampleRing ring = rings.get(connectionId);
        return ring == null ? List.of() : ring.toList();
    }

    /** The newest reading, which is what a rate is measured against. */
    public MetricsSample latest(Long connectionId) {
        SampleRing ring = rings.get(connectionId);
        return ring == null ? null : ring.latest();
    }

    /** Forgets a target when its profile is deleted, rather than when a dashboard closes. */
    void onRemoved(@Observes ConnectionRemoved removed) {
        rings.remove(removed.id());
    }
}
