package io.keydra.connections.registry;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Re-probes every registered connection on a fixed interval so the UI sees up/down transitions
 * without polling.
 *
 * <p>Runs on a Vert.x timer and deliberately touches no database state — it sweeps the registry's
 * cached clients, so a profile joins the rotation the first time something asks for its status and
 * leaves it when the profile is deleted.
 */
@ApplicationScoped
public class ConnectionHealthMonitor {

    private final Vertx vertx;
    private final ConnectionRegistry registry;
    private final Duration interval;

    @Inject
    ConnectionHealthMonitor(
            Vertx vertx,
            ConnectionRegistry registry,
            @ConfigProperty(name = "keydra.connections.health-check-interval", defaultValue = "10s")
                    Duration interval) {
        this.vertx = vertx;
        this.registry = registry;
        this.interval = interval;
    }

    private static final Logger LOG = Logger.getLogger(ConnectionHealthMonitor.class);

    private long timerId = -1;

    void onStart(@Observes StartupEvent event) {
        timerId = vertx.setPeriodic(interval.toMillis(), id -> sweep());
        LOG.infof("Connection health checks every %s", interval);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    void sweep() {
        for (Long id : registry.registeredIds()) {
            registry.refreshRegistered(id)
                    .subscribe()
                    .with(
                            ignored -> {},
                            failure ->
                                    LOG.debugf(failure, "Health check failed for profile %d", id));
        }
    }
}
