package io.keydra.authz.service;

import io.keydra.authz.persistence.SessionRepository;
import io.keydra.common.vertx.OwnContext;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * Writes down that a session was used, out of the way of the request that used it.
 *
 * <p>Nothing waits for this, which is the point — a timestamp nobody reads to the minute should not
 * put a write in front of every signed-in call. But "nothing waits for it" was being spelled as
 * subscribing to it and walking away, on the request's own context, which means on the request's
 * own Hibernate session. A session serves one statement at a time: an update started there and left
 * running lands in the middle of whatever the request reads next, and what comes back is Hibernate
 * complaining about its own state — {@code Illegal pop()}, {@code NoSuchElementException}, a
 * session reported closed — blamed on the read rather than on the write that was still going.
 *
 * <p>So it gets a context of its own, and a transaction of its own, and its failures are its own
 * too: a "last used" that did not land is not a reason to fail somebody's request.
 *
 * <p>A separate bean rather than a method next door, because {@code @WithTransaction} is an
 * interceptor and a class calling itself never passes through one.
 */
@ApplicationScoped
public class SessionTouches {

    private static final Logger LOG = Logger.getLogger(SessionTouches.class);

    private final SessionRepository sessions;
    private final Vertx vertx;

    @Inject
    SessionTouches(SessionRepository sessions, Vertx vertx) {
        this.sessions = sessions;
        this.vertx = vertx;
    }

    /** Notes the use and returns at once; the write happens somewhere else. */
    public void note(String id, Instant at) {
        OwnContext.run(
                vertx,
                () -> write(id, at),
                failure -> LOG.debugf(failure, "Could not note that session %s was used", id));
    }

    @WithTransaction
    Uni<Integer> write(String id, Instant at) {
        return sessions.touch(id, at);
    }
}
