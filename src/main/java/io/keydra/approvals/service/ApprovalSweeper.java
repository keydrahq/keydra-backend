package io.keydra.approvals.service;

import io.keydra.cluster.service.Leadership;
import io.keydra.common.vertx.OwnContext;
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
 * Ends the requests nobody answered.
 *
 * <p>An ending rather than a deletion, and it is the point of the expiry: the failure being
 * prevented is somebody believing an operation is arranged when it is never going to happen, so the
 * row stays and says what became of it. A purge agreed to three weeks late would be an agreement to
 * a sentence rather than to a state of the world.
 *
 * <p>Asked of {@link Leadership} at the moment it would happen, like every other job that must run
 * once where there is more than one Keydra, and on a context of its own for the reason every timer
 * here is.
 *
 * <p>A timer and nothing else: what to do lives on {@link ApprovalService}, which is the same
 * arrangement {@code SessionSweeper} has with {@code Sessions}. A clock is not a thing a test can
 * wait for, and the behaviour worth testing is what expiring does rather than when it happens.
 */
@ApplicationScoped
public class ApprovalSweeper {

    private static final Logger LOG = Logger.getLogger(ApprovalSweeper.class);

    private final Vertx vertx;
    private final ApprovalService service;
    private final Leadership leadership;
    private final Duration interval;

    private long timerId = -1;

    @Inject
    ApprovalSweeper(
            Vertx vertx,
            ApprovalService service,
            Leadership leadership,
            @ConfigProperty(name = "keydra.approvals.sweep-interval", defaultValue = "5m")
                    Duration interval) {
        this.vertx = vertx;
        this.service = service;
        this.leadership = leadership;
        this.interval = interval;
    }

    void onStart(@Observes StartupEvent ignored) {
        timerId = vertx.setPeriodic(interval.toMillis(), id -> sweep());
        LOG.debugf("Ending unanswered approval requests every %s", interval);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    private void sweep() {
        if (!leadership.isLeader()) {
            return;
        }
        OwnContext.run(
                vertx,
                service::expire,
                failure -> LOG.debug("Could not end the unanswered approval requests", failure));
    }
}
