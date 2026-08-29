package io.keydra.topology.service;

import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.Capabilities;
import io.keydra.engine.ClusterHealth;
import io.keydra.engine.ClusterNode;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeyValueEngine;
import io.keydra.engine.SentinelMaster;
import io.keydra.topology.dto.TargetTopology;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Describes how a target is arranged and what it supports.
 *
 * <p>The three questions are asked together and in parallel: they are independent, and asking in
 * sequence would make a page that opens on this wait for three round trips instead of one.
 */
@ApplicationScoped
public class TopologyService {

    private final ConnectionService connections;
    private final EngineSelector engines;

    @Inject
    TopologyService(ConnectionService connections, EngineSelector engines) {
        this.connections = connections;
        this.engines = engines;
    }

    public Uni<TargetTopology> describe(Long connectionId) {
        return connections.load(connectionId).flatMap(this::describe);
    }

    /**
     * What a target can do, without asking how it is arranged.
     *
     * <p>Its own entry point because its own caller wants only this: the tab bar decides which
     * tools to offer for a target, and drawing a tab bar should not cost a walk of a cluster's
     * nodes and its sentinels. Half of the answer needs no round trip at all — whether a store has
     * a command language is a fact about the engine.
     */
    public Uni<Capabilities> capabilities(Long connectionId) {
        return connections
                .load(connectionId)
                .flatMap(profile -> engines.forProfile(profile).capabilities(profile));
    }

    private Uni<TargetTopology> describe(ConnectionProfile profile) {
        KeyValueEngine engine = engines.forProfile(profile);

        Uni<ServerInfo> server = engine.describe(profile);
        Uni<Capabilities> capabilities = engine.capabilities(profile);
        Uni<List<ClusterNode>> nodes =
                engine.topology()
                        .map(topology -> topology.clusterNodes(profile))
                        .orElseGet(() -> Uni.createFrom().item(List.of()));
        Uni<List<SentinelMaster>> sentinels =
                engine.topology()
                        .map(topology -> topology.sentinelMasters(profile))
                        .orElseGet(() -> Uni.createFrom().item(List.of()));
        // Asked alongside the node list rather than after it: the two are one answer, and the page
        // that draws them cannot say anything true from either half on its own.
        Uni<ClusterHealth> health =
                engine.topology()
                        .map(topology -> topology.clusterHealth(profile))
                        .orElseGet(() -> Uni.createFrom().nullItem());

        return Uni.combine()
                .all()
                .unis(server, capabilities, nodes, sentinels, health)
                .with(
                        answers ->
                                new TargetTopology(
                                        (ServerInfo) answers.get(0),
                                        (Capabilities) answers.get(1),
                                        castNodes(answers.get(2)),
                                        castMasters(answers.get(3)),
                                        (ClusterHealth) answers.get(4)));
    }

    @SuppressWarnings("unchecked")
    private static List<ClusterNode> castNodes(Object nodes) {
        return (List<ClusterNode>) nodes;
    }

    @SuppressWarnings("unchecked")
    private static List<SentinelMaster> castMasters(Object masters) {
        return (List<SentinelMaster>) masters;
    }
}
