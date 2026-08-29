package io.keydra.engine.aerospike;

import com.aerospike.client.Info;
import com.aerospike.client.cluster.Node;
import io.keydra.common.vertx.OffLoop;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.ClientConnection;
import io.keydra.engine.Database;
import io.keydra.engine.KeySize;
import io.keydra.engine.MetricsSample;
import io.keydra.engine.ServerMetrics;
import io.keydra.engine.SlowCommand;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an Aerospike server will say about itself.
 *
 * <p>Enough of it to draw a dashboard, and honest about the rest. Every reading below was taken
 * from a running server rather than from a list of field names — which matters here more than
 * usual, because Aerospike reports the same word in two places meaning two things: {@code objects}
 * in the cluster statistics counts every namespace, and {@code objects} under one namespace counts
 * that one. A profile points at one namespace, so it is the second that answers "how many keys".
 *
 * <p>What has no reading and is left null rather than filled with a plausible number: operations a
 * second, which Aerospike does not count, and a memory high-water mark, which it does not keep. A
 * chart drawn from an invented figure is worse than a chart with a gap in it.
 *
 * <p>Every call here is on a worker thread. Aerospike's info command is blocking and has no
 * reactive form, and this application does not put blocking work on an event loop.
 */
@ApplicationScoped
public class AerospikeMetrics implements ServerMetrics {

    private final AerospikeClients clients;

    @Inject
    AerospikeMetrics(AerospikeClients clients) {
        this.clients = clients;
    }

    @Override
    public Uni<MetricsSample> sample(ConnectionProfile profile) {
        return offLoop(
                profile,
                node -> {
                    Map<String, String> cluster = fields(node, "statistics");
                    Map<String, String> namespace = fields(node, "namespace/" + profile.namespace);
                    Long reads = number(namespace, "client_read_success");
                    Long writes = number(namespace, "client_write_success");
                    return new MetricsSample(
                            Instant.now(),
                            // What this namespace is holding, not what the process asked the
                            // operating system for: the second is true of the server and says
                            // nothing about the data somebody came here to look at.
                            number(namespace, "data_used_bytes"),
                            // Aerospike keeps no high-water mark.
                            null,
                            number(namespace, "data_total_bytes"),
                            number(cluster, "client_connections"),
                            // Nor a rate. Two samples and the clock between them is the only way
                            // to have one, and inventing it here would put a number on a chart
                            // that nothing measured.
                            null,
                            reads == null || writes == null ? null : reads + writes,
                            reads,
                            number(namespace, "client_read_not_found"),
                            number(namespace, "objects"),
                            number(cluster, "uptime"),
                            number(namespace, "evicted_objects"),
                            number(namespace, "expired_objects"));
                });
    }

    @Override
    public Uni<Map<String, Map<String, String>>> info(ConnectionProfile profile, String section) {
        String asked = section == null || section.isBlank() ? "statistics" : section;
        return offLoop(profile, node -> Map.of(asked, fields(node, asked)));
    }

    /**
     * The namespace, as the one database it is.
     *
     * <p>Aerospike numbers nothing — a namespace is named and a profile points at one — so this
     * answers with a single entry rather than the sixteen a RESP store has. It is here because the
     * count on it is what the browser shows beside the key list, and that count is worth having.
     */
    @Override
    public Uni<List<Database>> databases(ConnectionProfile profile) {
        return offLoop(
                profile,
                node -> {
                    Map<String, String> namespace = fields(node, "namespace/" + profile.namespace);
                    long objects = value(number(namespace, "objects"));
                    return List.of(
                            new Database(0, objects, value(number(namespace, "expired_objects"))));
                });
    }

    /*
     * The four below are things Aerospike does not have, answered as absences rather than as
     * failures. Nothing asks for them anyway — the capability matrix says the store has no slow log
     * and no client list, so the pages that would call these are not offered — but an interface
     * that is not offered is not the same as one that cannot be reached, and a method that threw
     * would turn a stray call into an error somebody has to explain.
     */

    @Override
    public Uni<List<SlowCommand>> slowCommands(ConnectionProfile profile, int limit) {
        return Uni.createFrom().item(List.of());
    }

    @Override
    public Uni<Void> clearSlowCommands(ConnectionProfile profile) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<List<ClientConnection>> clients(ConnectionProfile profile) {
        return Uni.createFrom().item(List.of());
    }

    @Override
    public Uni<Boolean> killClient(ConnectionProfile profile, String clientId) {
        return Uni.createFrom().item(false);
    }

    /**
     * Measuring keys, which Aerospike does not offer per record.
     *
     * <p>Empty rather than a sample of guesses. Its storage is sized by namespace and by device,
     * and there is no equivalent of asking what one record costs — a biggest-keys report built from
     * anything else here would be a ranking of numbers nobody measured.
     */
    @Override
    public Multi<KeySize> measureKeys(ConnectionProfile profile, int sampleSize) {
        return Multi.createFrom().empty();
    }

    /** Runs one blocking info call away from the event loop, against whichever node answers. */
    private <T> Uni<T> offLoop(
            ConnectionProfile profile, java.util.function.Function<Node, T> ask) {
        return OffLoop.call(
                () -> {
                    Node[] nodes = clients.forProfile(profile).getAerospikeClient().getNodes();
                    if (nodes.length == 0) {
                        throw new IllegalStateException("No Aerospike node answered");
                    }
                    return ask.apply(nodes[0]);
                });
    }

    /** One info answer, which arrives as {@code a=1;b=2} and is read into a map. */
    private static Map<String, String> fields(Node node, String command) {
        String answer = Info.request(null, node, command);
        Map<String, String> read = new LinkedHashMap<>();
        if (answer == null) {
            return read;
        }
        for (String pair : answer.split(";")) {
            int cut = pair.indexOf('=');
            if (cut > 0) {
                read.put(pair.substring(0, cut), pair.substring(cut + 1));
            }
        }
        return read;
    }

    private static Long number(Map<String, String> fields, String name) {
        String raw = fields.get(name);
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static long value(Long number) {
        return number == null ? 0 : number;
    }
}
