package io.keydra.monitoring.sink;

import io.keydra.engine.MetricsSample;
import io.smallrye.mutiny.Uni;
import java.time.Instant;
import java.util.List;

/**
 * Somewhere a reading is written.
 *
 * <p>An interface rather than a branch, for the reason the engines and the backup destinations are:
 * what a reading is written to is a deployment's decision, not a fact about the code that takes it.
 * The ring buffer is one of these and stays the default, so an instance with nothing configured
 * behaves exactly as it did before there was a second one.
 *
 * <p>Writing never blocks and never fails. A sampler that waits on a store is a sampler that stops
 * sampling when the store is slow — which is precisely when the readings matter — and a sink that
 * can fail a reading is a sink that can take the dashboard down with it.
 */
public interface MetricsSink {

    /** Takes a reading. Returns immediately, whatever the sink then does with it. */
    void write(Long connectionId, MetricsSample sample);

    /**
     * Whether what is written here outlives the process.
     *
     * <p>Asked so an interface can say which of two different claims it is making: an hour from
     * memory and a month from a store are not the same answer with different limits.
     */
    boolean isDurable();

    /**
     * Readings between two instants, oldest first.
     *
     * <p>{@code points} is a budget rather than a promise: a month of five-second readings is half
     * a million rows and a chart has a few hundred pixels, so a sink that can aggregate is expected
     * to. One that cannot answer the range at all answers with an empty list.
     */
    Uni<List<MetricsSample>> between(Long connectionId, Instant from, Instant to, int points);
}
