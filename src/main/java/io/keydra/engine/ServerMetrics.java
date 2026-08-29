package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Map;

/**
 * What a store can say about itself.
 *
 * <p>Optional on {@link KeyValueEngine} like the console and the message bus: a store may expose no
 * statistics at all, and an empty Optional says so better than a method that throws.
 */
public interface ServerMetrics {

    /** One reading of the vital signs, for the dashboard and for the ring buffer behind it. */
    Uni<MetricsSample> sample(ConnectionProfile profile);

    /**
     * Raw statistics, grouped by section.
     *
     * <p>Free-form on purpose: {@link MetricsSample} carries the dozen fields a dashboard draws,
     * and this carries everything else for someone who came looking for a specific number.
     *
     * @param section a section name, or null for everything the store will give
     */
    Uni<Map<String, Map<String, String>>> info(ConnectionProfile profile, String section);

    /** Recent slow commands, newest first. */
    Uni<List<SlowCommand>> slowCommands(ConnectionProfile profile, int limit);

    /** Clears the slow log, which is the only way to stop old entries crowding out new ones. */
    Uni<Void> clearSlowCommands(ConnectionProfile profile);

    Uni<List<ClientConnection>> clients(ConnectionProfile profile);

    /**
     * The databases this target holds, and how much is in each.
     *
     * <p>Every one the server is configured for, not only the ones with something in them: a list
     * that hides the empty ones cannot be used to move into one.
     */
    Uni<List<Database>> databases(ConnectionProfile profile);

    /**
     * Disconnects one client.
     *
     * @return true when the store had a client with that id
     */
    Uni<Boolean> killClient(ConnectionProfile profile, String clientId);

    /**
     * Measures keys as they are walked, so the caller can rank them.
     *
     * <p>A stream rather than a list because measuring is one round trip per key: emitting as it
     * goes lets a caller stop early, and lets the UI show a partial ranking rather than nothing
     * until the whole sample is done.
     *
     * <p>This is a sample, never a census. Measuring every key in a large keyspace would cost more
     * than the answer is worth, so the caller says how many keys to look at and the result is
     * honest about being drawn from that many.
     *
     * @param sampleSize how many keys to measure
     */
    Multi<KeySize> measureKeys(ConnectionProfile profile, int sampleSize);
}
