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
 * Removes sessions that have lapsed.
 *
 * <p>A session table nobody prunes is a table that grows for as long as the application runs, and
 * what it grows with is a record of where somebody was working and when. Expired rows answer no
 * question — the identity behind them stopped working at the expiry, and the list a person reads
 * shows only what is live — so keeping them is keeping a log for its own sake.
 *
 * <p>Asked of {@link Leadership} at the moment it would happen, like every other job that must run
 * once where there is more than one Keydra. On a context of its own, because a timer that joined
 * whatever context it fired on would be joining a session a finished request has already closed.
 */
@ApplicationScoped
public class SessionSweeper {

    private static final Logger LOG = Logger.getLogger(SessionSweeper.class);

    private final Vertx vertx;
    private final Sessions sessions;
    private final Leadership leadership;
    private final Duration interval;

    private long timerId = -1;

    @Inject
    SessionSweeper(
            Vertx vertx,
            Sessions sessions,
            Leadership leadership,
            @ConfigProperty(name = "keydra.sessions.sweep-interval", defaultValue = "1h")
                    Duration interval) {
        this.vertx = vertx;
        this.sessions = sessions;
        this.leadership = leadership;
        this.interval = interval;
    }

    void onStart(@Observes StartupEvent ignored) {
        timerId = vertx.setPeriodic(interval.toMillis(), id -> sweep());
        LOG.debugf("Sweeping lapsed sessions every %s", interval);
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
                () ->
                        sessions.sweep()
                                .invoke(
                                        removed -> {
                                            if (removed > 0) {
                                                LOG.debugf("Removed %d lapsed sessions", removed);
                                            }
                                        }),
                failure -> LOG.debug("Could not sweep the lapsed sessions", failure));
    }
}
