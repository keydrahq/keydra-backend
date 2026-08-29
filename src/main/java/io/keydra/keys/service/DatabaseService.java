package io.keydra.keys.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.Database;
import io.keydra.engine.EngineSelector;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The numbered keyspaces inside one target.
 *
 * <p>A standalone RESP server holds several, sharing a process and a memory limit but nothing else:
 * a key in one is invisible from another. Clustered and sentinel targets hold exactly one, and
 * saying so is more useful than offering a list of fifteen that cannot be entered.
 */
@ApplicationScoped
public class DatabaseService {

    private final ConnectionService connections;
    private final EngineSelector engines;

    @Inject
    DatabaseService(ConnectionService connections, EngineSelector engines) {
        this.connections = connections;
        this.engines = engines;
    }

    public Uni<List<Database>> list(Long connectionId) {
        return connections.load(connectionId).flatMap(this::listFor);
    }

    private Uni<List<Database>> listFor(ConnectionProfile profile) {
        if (profile.type != ConnectionType.STANDALONE) {
            // Cluster and sentinel have one keyspace, and SELECT against them is an error
            // rather than a no-op. The count still comes from the server.
            return engines.forProfile(profile)
                    .metrics()
                    .map(metrics -> metrics.databases(profile).map(all -> all.subList(0, 1)))
                    .orElseGet(() -> Uni.createFrom().item(List.of(new Database(0, 0, 0))));
        }
        return engines.forProfile(profile)
                .metrics()
                .map(metrics -> metrics.databases(profile))
                .orElseGet(() -> Uni.createFrom().item(List.of(new Database(0, 0, 0))));
    }
}
