package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.engine.ClusterHealth;
import io.keydra.engine.ClusterNode;
import io.keydra.engine.SentinelMaster;
import io.keydra.engine.Topology;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How a RESP target is arranged.
 *
 * <p>Both questions answer with an empty list rather than an error when the target is not in that
 * arrangement. A standalone server has no cluster and no sentinel, which is a fact about it, not a
 * failure to find out.
 */
@ApplicationScoped
public class RespTopology implements Topology {

    private final RespConnectionPool pool;

    @Inject
    RespTopology(RespConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Every node, and every reshard running through them.
     *
     * <p>Asked of every primary rather than of the one this connection landed on. Each node
     * describes the same arrangement, so any one answer is the shape of the cluster — but only a
     * node's own {@code myself} line names the slots it is moving, so a reshard between two other
     * nodes is invisible from a single vantage point. That was the honest half-answer phase 36
     * shipped, and this is the whole one.
     *
     * <p>Affordable because the client already holds a connection to every node: it is a round trip
     * per node in parallel rather than a connection per node, and this page is opened deliberately
     * rather than polled.
     *
     * <p>Falling back to the single ask is not a fallback so much as the right question for
     * anything that is not a cluster: {@code onAllMasterNodes} needs a cluster client, and a
     * standalone server has one node whose own line is the whole story.
     */
    @Override
    public Uni<List<ClusterNode>> clusterNodes(ConnectionProfile profile) {
        if (profile.type != ConnectionType.CLUSTER) {
            return fromOneNode(profile);
        }
        return pool.sendToEveryPrimary(profile, Request.cmd(Command.CLUSTER).arg("NODES"))
                .map(RespTopology::merged)
                .onFailure()
                // A cluster client that cannot reach every node still knows what one node said.
                .recoverWithUni(() -> fromOneNode(profile));
    }

    private Uni<List<ClusterNode>> fromOneNode(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.CLUSTER).arg("NODES"))
                .map(
                        response ->
                                RespClusterNodes.parse(
                                        response == null ? null : response.toString()))
                .onFailure()
                // "This instance has cluster support disabled" is an answer, not a fault.
                .recoverWithItem(List.of());
    }

    /**
     * One arrangement, with every node's own account of what it is moving.
     *
     * <p>The first answer that says anything is the arrangement — they all describe the same
     * cluster, and disagreeing about it is a cluster that is mid-gossip rather than a thing to
     * merge. What is taken from each of the others is only its {@code myself} line's migrations,
     * because that is the only part of a node's description that only that node can give.
     */
    private static List<ClusterNode> merged(List<Response> replies) {
        List<List<ClusterNode>> answers =
                replies.stream()
                        .map(
                                reply ->
                                        RespClusterNodes.parse(
                                                reply == null ? null : reply.toString()))
                        .filter(nodes -> !nodes.isEmpty())
                        .toList();
        if (answers.isEmpty()) {
            return List.of();
        }

        Map<String, List<ClusterNode.SlotMigration>> moving = new HashMap<>();
        for (List<ClusterNode> answer : answers) {
            for (ClusterNode node : answer) {
                if (node.isSelf() && !node.migrations().isEmpty()) {
                    moving.put(node.id(), node.migrations());
                }
            }
        }

        return answers.get(0).stream()
                .map(
                        node ->
                                moving.containsKey(node.id())
                                        ? withMigrations(node, moving.get(node.id()))
                                        : node)
                .toList();
    }

    private static ClusterNode withMigrations(
            ClusterNode node, List<ClusterNode.SlotMigration> migrations) {
        return new ClusterNode(
                node.id(),
                node.address(),
                node.role(),
                node.isSelf(),
                node.primaryId(),
                node.slots(),
                node.linkState(),
                node.flags(),
                migrations);
    }

    /**
     * The cluster's own verdict, which the node list cannot give.
     *
     * <p>Null rather than an error when the command is refused: a standalone server answers "this
     * instance has cluster support disabled", which is an answer about what it is.
     */
    @Override
    public Uni<ClusterHealth> clusterHealth(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.CLUSTER).arg("INFO"))
                .map(
                        response ->
                                RespClusterHealth.parse(
                                        response == null ? null : response.toString()))
                .onFailure()
                .recoverWithItem(() -> null);
    }

    @Override
    public Uni<List<SentinelMaster>> sentinelMasters(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.SENTINEL).arg("MASTERS"))
                .flatMap(response -> withReplicas(profile, toMasters(response)))
                .onFailure()
                .recoverWithItem(List.of());
    }

    /**
     * Fills in each primary's replicas.
     *
     * <p>Sentinel reports them separately, one request per primary. Done in parallel rather than in
     * sequence: a sentinel watching a dozen primaries would otherwise take a dozen round trips end
     * to end.
     */
    private Uni<List<SentinelMaster>> withReplicas(
            ConnectionProfile profile, List<SentinelMaster> masters) {
        if (masters.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<Uni<SentinelMaster>> completed =
                masters.stream().map(master -> withReplicas(profile, master)).toList();
        return Uni.join().all(completed).andCollectFailures();
    }

    private Uni<SentinelMaster> withReplicas(ConnectionProfile profile, SentinelMaster master) {
        return pool.send(profile, Request.cmd(Command.SENTINEL).arg("REPLICAS").arg(master.name()))
                .map(
                        response ->
                                master.replicas().isEmpty()
                                        ? toReplicas(response)
                                        : master.replicas())
                .onFailure()
                .recoverWithItem(List.<SentinelMaster.Replica>of())
                .map(
                        replicas ->
                                new SentinelMaster(
                                        master.name(),
                                        master.address(),
                                        master.status(),
                                        master.quorum(),
                                        replicas));
    }

    /** SENTINEL MASTERS answers with one flat field/value array per primary. */
    private static List<SentinelMaster> toMasters(Response response) {
        if (response == null) {
            return List.of();
        }
        List<SentinelMaster> masters = new ArrayList<>(response.size());
        response.forEach(
                entry -> {
                    var fields = RespFields.pairs(entry);
                    masters.add(
                            new SentinelMaster(
                                    fields.get("name"),
                                    address(fields.get("ip"), fields.get("port")),
                                    fields.getOrDefault("flags", "unknown"),
                                    RespFields.integer(fields.get("quorum")),
                                    List.of()));
                });
        return masters;
    }

    private static List<SentinelMaster.Replica> toReplicas(Response response) {
        if (response == null) {
            return List.of();
        }
        List<SentinelMaster.Replica> replicas = new ArrayList<>(response.size());
        response.forEach(
                entry -> {
                    var fields = RespFields.pairs(entry);
                    replicas.add(
                            new SentinelMaster.Replica(
                                    address(fields.get("ip"), fields.get("port")),
                                    fields.getOrDefault("flags", "unknown"),
                                    fields.getOrDefault("master-link-status", "unknown")));
                });
        return replicas;
    }

    private static String address(String host, String port) {
        return host == null ? null : host + ":" + (port == null ? "" : port);
    }
}
