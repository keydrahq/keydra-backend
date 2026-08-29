package io.keydra.telemetry.service;

import io.keydra.cluster.service.Leadership;
import io.keydra.connections.dto.ConnectionState;
import io.keydra.connections.registry.ConnectionRegistry;
import io.keydra.engine.EngineTraffic;
import io.keydra.keys.service.KeyMigrationService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Registers the numbers that are true rather than the ones that happened.
 *
 * <p>Here rather than in the domains themselves, so nothing that does the work has to import a
 * metrics library to be counted. A gauge is read at scrape time, so all this asks of a domain is a
 * method that already existed: how many targets are up, how many migrations are walking, whether
 * this instance holds the chores.
 */
@ApplicationScoped
public class KeydraGauges {

    private final KeydraMeters meters;
    private final ConnectionRegistry connections;
    private final EngineTraffic engines;
    private final KeyMigrationService migrations;
    private final Leadership leadership;

    @Inject
    KeydraGauges(
            KeydraMeters meters,
            ConnectionRegistry connections,
            EngineTraffic engines,
            KeyMigrationService migrations,
            Leadership leadership) {
        this.meters = meters;
        this.connections = connections;
        this.engines = engines;
        this.migrations = migrations;
        this.leadership = leadership;
    }

    void onStart(@Observes StartupEvent ignored) {
        meters.watchTargets(
                () -> connections.countInState(ConnectionState.UP),
                () -> connections.countInState(ConnectionState.DOWN));
        meters.watchMigrations(migrations::runningCount);
        // A gauge rather than a label on everything else: whether this instance is the one
        // doing the chores changes while the process runs, and a label that changes is a new
        // series every time it does.
        meters.watchChores(() -> leadership.isLeader() ? 1 : 0);
        // The number the instances page has drawn since phase 41, now somewhere it can be
        // graphed. Read from where the commands leave rather than counted a second time here.
        meters.watchCommands(engines::commandCount);
    }
}
