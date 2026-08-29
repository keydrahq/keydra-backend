package io.keydra.authz.service;

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
 * Drops sign-in attempts past the retention window.
 *
 * <p>A table nobody prunes grows for as long as the application runs, which is the reason the
 * sessions have a sweeper too. This one has a second reason that matters more: what it holds is a
 * record of when people signed in and roughly from where, and a record like that kept for ever is a
 * thing worth stealing rather than a thing worth having. The window is long enough to see an attack
 * being made slowly and no longer.
 *
 * <p>Asks {@link Leadership} at the moment the work would happen. More than one Keydra runs against
 * one database and only the instance holding the lease should be deleting from it.
 */
@ApplicationScoped
public class SignInSweeper {

    private static final Logger LOG = Logger.getLogger(SignInSweeper.class);

    private final Vertx vertx;
    private final SignInActivityService activity;
    private final Leadership leadership;
    private final Duration interval;

    private long timerId = -1;

    @Inject
    SignInSweeper(
            Vertx vertx,
            SignInActivityService activity,
            Leadership leadership,
            @ConfigProperty(name = "keydra.security.sign-in.sweep-interval", defaultValue = "6h")
                    Duration interval) {
        this.vertx = vertx;
        this.activity = activity;
        this.leadership = leadership;
        this.interval = interval;
    }

    void onStart(@Observes StartupEvent ignored) {
        timerId = vertx.setPeriodic(interval.toMillis(), id -> sweep());
        LOG.debugf("Sweeping expired sign-in attempts every %s", interval);
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
                activity::sweep,
                failure -> LOG.debug("Could not sweep the expired sign-in attempts", failure));
    }
}
