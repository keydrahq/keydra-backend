package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.engine.ClusterNode;
import io.keydra.engine.KeyQuery;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Walks a keyspace with SCAN.
 *
 * <p>Its own bean because more than one thing needs to walk a keyspace without describing what it
 * finds — the key browser's namespace tree and the memory sampler both do — and having each keep
 * its own cursor loop is how two walks end up filtering differently.
 *
 * <p>SCAN, never KEYS: KEYS blocks the server for the length of the keyspace, which on the datasets
 * this has to handle means stalling every other client.
 */
@ApplicationScoped
public class RespKeyScanner {

    /** Redis' own "iteration finished" cursor, which is also where one starts. */
    static final String CURSOR_END = "0";

    /** How long a cluster's master list is worth reusing between pages of one walk. */
    private static final Duration TOPOLOGY_TTL = Duration.ofSeconds(30);

    private final RespConnectionPool pool;
    private final RespTopology topology;
    private final Map<Long, Known> known = new ConcurrentHashMap<>();

    @Inject
    RespKeyScanner(RespConnectionPool pool, RespTopology topology) {
        this.pool = pool;
        this.topology = topology;
    }

    /** One cursor step carrying names alone. */
    record NamePage(String cursor, List<String> names) {}

    /** Every key name matching the query, a page at a time. */
    public Multi<String> names(ConnectionProfile profile, KeyQuery query) {
        AtomicReference<String> cursor = new AtomicReference<>(CURSOR_END);
        return Multi.createBy()
                .repeating()
                .uni(
                        () -> cursor,
                        state ->
                                namePage(profile, state.get(), query)
                                        .invoke(p -> state.set(p.cursor())))
                // The final page carries cursor 0; emit it, then stop.
                .whilst(page -> !CURSOR_END.equals(page.cursor()))
                .flatMap(page -> Multi.createFrom().iterable(page.names()));
    }

    /**
     * One SCAN step, stopping at the names.
     *
     * <p>A cluster is walked node by node and everything else in one loop. SCAN has no key, so the
     * cluster client sends it wherever it likes and hands back that node's cursor; a walk driven by
     * that one cursor finishes when one node finishes and reports a third of a keyspace as all of
     * it. It showed up as a migration that moved 1,502 of 24,552 keys and called itself done, a
     * namespace that would not delete, and counts that were simply wrong.
     *
     * <p>The cursor stays one opaque string, because everything above this pages through it and
     * none of it should learn what a hash slot is. For a cluster it carries which master is being
     * walked as well as where in it — {@code "2|4096"} — and the two ends keep their meaning: "0"
     * going in starts the walk, and "0" coming back ends it. A node that finishes hands over to the
     * next; the last one to finish ends the whole thing.
     */
    public Uni<NamePage> namePage(ConnectionProfile profile, String cursor, KeyQuery query) {
        if (profile.type != ConnectionType.CLUSTER) {
            return pool.send(profile, request(cursor, query)).map(RespKeyScanner::toPage);
        }
        return masters(profile).flatMap(masters -> nodePage(profile, masters, cursor, query));
    }

    private Uni<NamePage> nodePage(
            ConnectionProfile profile, List<String> masters, String cursor, KeyQuery query) {
        if (masters.isEmpty()) {
            // Nothing answered CLUSTER NODES. Better to walk the cluster as the client routes it
            // than to report an empty keyspace.
            return pool.send(profile, request(cursor, query)).map(RespKeyScanner::toPage);
        }
        Walk walk = Walk.of(cursor, masters.size());
        return pool.sendToNode(profile, masters.get(walk.node()), request(walk.cursor(), query))
                .map(
                        response -> {
                            String next = response.get(0).toString();
                            return new NamePage(
                                    walk.next(next, masters.size()), toNames(response.get(1)));
                        });
    }

    /**
     * Where a walk of a cluster has got to: which master, and where in it.
     *
     * @param node the master's position in the list, which is stable for as long as the topology is
     * @param cursor that master's own SCAN cursor
     */
    private record Walk(int node, String cursor) {

        private static final String SEPARATOR = "|";

        static Walk of(String cursor, int masters) {
            if (cursor == null || cursor.isBlank() || CURSOR_END.equals(cursor)) {
                return new Walk(0, CURSOR_END);
            }
            int at = cursor.indexOf(SEPARATOR);
            if (at < 0) {
                // A plain cursor from before this existed, or from somewhere else. Start over
                // rather than send a number to a node it did not come from.
                return new Walk(0, CURSOR_END);
            }
            try {
                int node = Integer.parseInt(cursor.substring(0, at));
                return new Walk(Math.max(0, Math.min(node, masters - 1)), cursor.substring(at + 1));
            } catch (NumberFormatException notOurs) {
                return new Walk(0, CURSOR_END);
            }
        }

        /** Where to resume: further into this master, on to the next, or finished. */
        String next(String nodeCursor, int masters) {
            if (!CURSOR_END.equals(nodeCursor)) {
                return node + SEPARATOR + nodeCursor;
            }
            return node + 1 < masters ? (node + 1) + SEPARATOR + CURSOR_END : CURSOR_END;
        }
    }

    /**
     * The masters, briefly remembered.
     *
     * <p>A walk asks once per page and a large keyspace is many pages, so the answer is held for a
     * moment rather than fetched every time. Short, because a cluster that has just failed over
     * should be walked as it is now — and a stale list costs a page from a node that is no longer a
     * master, not a wrong answer.
     */
    Uni<List<String>> masters(ConnectionProfile profile) {
        Known remembered = known.get(profile.id);
        if (remembered != null && remembered.until().isAfter(Instant.now())) {
            return Uni.createFrom().item(remembered.addresses());
        }
        return topology.clusterNodes(profile)
                .map(
                        nodes ->
                                nodes.stream()
                                        .filter(
                                                node ->
                                                        ClusterNode.ROLE_PRIMARY.equals(
                                                                node.role()))
                                        .map(ClusterNode::address)
                                        .filter(address -> address != null && !address.isBlank())
                                        .toList())
                .invoke(
                        addresses -> {
                            if (profile.id != null) {
                                known.put(
                                        profile.id,
                                        new Known(addresses, Instant.now().plus(TOPOLOGY_TTL)));
                            }
                        })
                .onFailure()
                .recoverWithItem(List.<String>of());
    }

    /** A remembered master list and when it stops being worth trusting. */
    private record Known(List<String> addresses, Instant until) {}

    private static NamePage toPage(Response response) {
        return new NamePage(response.get(0).toString(), toNames(response.get(1)));
    }

    /** Builds one SCAN, shared by every walk so their filtering cannot drift apart. */
    public static Request request(String cursor, KeyQuery query) {
        Request scan = Request.cmd(Command.SCAN).arg(cursor).arg("COUNT").arg(query.count());
        if (query.match() != null && !query.match().isBlank()) {
            scan.arg("MATCH").arg(query.match());
        }
        if (query.type() != null && !query.type().isBlank()) {
            // Filtering server-side keeps unwanted keys off the wire entirely.
            scan.arg("TYPE").arg(query.type());
        }
        return scan;
    }

    static List<String> toNames(Response keys) {
        List<String> names = new ArrayList<>(keys.size());
        keys.forEach(key -> names.add(key.toString()));
        return names;
    }
}
