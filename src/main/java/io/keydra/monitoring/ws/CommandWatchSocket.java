package io.keydra.monitoring.ws;

import io.keydra.authz.service.SessionEndings;
import io.keydra.authz.service.Sessions;
import io.keydra.monitoring.dto.CommandFrame;
import io.keydra.monitoring.service.CommandWatchService;
import io.keydra.security.Roles;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * What a target is being asked to do, as it happens.
 *
 * <p>A socket rather than an endpoint that returns, because there is no end: the stream runs until
 * the reader closes it, and closing it is what stops the watch on the server too.
 *
 * <p>Restricted to operators and administrators. This is the most revealing thing Keydra can show —
 * every command every client sends, including the ones carrying data — so a viewer who may only
 * read one key at a time has no business reading all of them as they go past.
 */
@WebSocket(
        path = "/api/v1/connections/{connectionId}/commands",
        endpointId = CommandWatchSocket.ENDPOINT_ID)
@RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
public class CommandWatchSocket {

    /**
     * Named so the roster can tell these sockets from the notification ones when it counts them.
     */
    public static final String ENDPOINT_ID = "keydra-command-watch";

    private final CommandWatchService watches;
    private final CurrentVertxRequest request;

    @Inject
    CommandWatchSocket(CommandWatchService watches, CurrentVertxRequest request) {
        this.watches = watches;
        this.request = request;
    }

    /**
     * Opens the watch and streams it.
     *
     * <p>Nothing is expected from the client after this: a watch has no controls beyond starting
     * and stopping, and stopping is closing the socket.
     */
    @OnOpen
    public Multi<CommandFrame> onOpen(
            @PathParam("connectionId") String connectionId, WebSocketConnection connection) {
        // Remembers which session opened the stream, so an ending can close it. Authorisation
        // happens once, when the socket opens; without this a session ended afterwards would
        // leave somebody watching a target's traffic on a session that no longer exists.
        String session = Sessions.presented(request.getCurrent());
        if (session != null) {
            connection.userData().put(SessionEndings.SESSION_KEY, session);
        }
        return watches.watch(Long.valueOf(connectionId));
    }
}
