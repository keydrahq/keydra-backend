package io.keydra.authz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.events.dto.Notification;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.store.service.KeydraStore;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Puts somebody out when their session ends, rather than waiting for them to ask again.
 *
 * <p>Ending a session stops the next request, which is enough for a page somebody is clicking
 * around. It is not enough for what is already open: a console socket is authorised when it is
 * opened and then carries commands for as long as it lives, so a session ended while one is open
 * would leave that person running commands on a session that no longer exists. The socket has to be
 * closed, and closed on whichever instance it is attached to — which where there is more than one
 * Keydra is not the instance the button was pressed on.
 *
 * <p>So an ending is published on the store, every instance hears it, and each one closes what it
 * holds for that session and tells the browser why. The browser is told separately from being cut
 * off, because a socket that simply drops looks like a network problem and a person who thinks it
 * is a network problem waits instead of signing in again.
 */
@ApplicationScoped
public class SessionEndings {

    private static final Logger LOG = Logger.getLogger(SessionEndings.class);

    private final KeydraStore store;
    private final OpenConnections connections;
    private final ObjectMapper mapper;

    @Inject
    SessionEndings(KeydraStore store, OpenConnections connections, ObjectMapper mapper) {
        this.store = store;
        this.connections = connections;
        this.mapper = mapper;
    }

    void onStart(@Observes StartupEvent ignored) {
        // Subscribed whichever store is in use. With the in-process one the publish is a local
        // call, which is what keeps this path exercised in every deployment rather than only in
        // the ones with a Redis — a fan-out bug otherwise waits there.
        store.subscribe(Sessions.ENDED_CHANNEL, this::onEnded);
    }

    /**
     * One session, ended somewhere.
     *
     * <p>Told to everybody and acted on by whoever holds something for it. The notification names
     * the session rather than the person: a browser knows which session it is, and everything else
     * on the socket learns only that some session somewhere ended.
     */
    private void onEnded(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String envelope = envelope();
        for (WebSocketConnection connection : connections) {
            if (!sessionId.equals(connection.userData().get(SESSION_KEY))) {
                continue;
            }
            // Told, then cut off. A socket that simply drops looks like a network problem, and
            // somebody who thinks it is a network problem waits instead of signing in again.
            if (envelope != null) {
                connection.sendText(envelope).subscribe().with(ignored -> {}, ignored -> {});
            }
            connection
                    .close()
                    .subscribe()
                    .with(
                            ignored ->
                                    LOG.debugf(
                                            "Closed a socket whose session ended: %s",
                                            connection.id()),
                            failure ->
                                    LOG.debugf(
                                            failure,
                                            "Could not close socket %s after its session ended",
                                            connection.id()));
        }
    }

    /** The message, in the same shape everything else on this socket arrives in. */
    private String envelope() {
        try {
            return mapper.writeValueAsString(
                    Notification.of(
                            NotificationCategory.SESSION_ENDED, Map.of("reason", "revoked")));
        } catch (Exception impossible) {
            LOG.debug("Could not write a session-ended notice", impossible);
            return null;
        }
    }

    /** Where a connection remembers which session opened it. */
    public static final UserData.TypedKey<String> SESSION_KEY =
            new UserData.TypedKey<>("keydra.session");
}
