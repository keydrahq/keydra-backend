package io.keydra.events.ws;

import io.keydra.authz.service.SessionEndings;
import io.keydra.authz.service.Sessions;
import io.keydra.events.service.SocketAudience;
import io.keydra.security.Roles;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * The single notification endpoint the whole frontend subscribes to.
 *
 * <p>Traffic is one-way. The endpoint exists so {@link io.keydra.events.service.NotificationHub}
 * has somewhere to push to; inbound frames are deliberately discarded, so no client can drive
 * server state through this socket.
 *
 * <p>One-way is not the same as harmless, which is why this asks who is listening. What goes out
 * here names targets, says which keys changed, carries a reading of every watched server and
 * repeats what an alert said — a description of somebody's estate, assembled and pushed. The lowest
 * role, because everything on this socket is something a viewer may already read; what it refuses
 * is nobody at all.
 *
 * <p>A role is not the whole of it, and for a long time it was. Being a viewer says somebody may
 * read a target they can see; it says nothing about which ones those are, and a grant model that
 * every other surface applies was not applied here. So the socket also works out, while it is being
 * opened, which targets this person may hear about — see {@link SocketAudience}. It is done in
 * {@code @OnOpen} and the socket is not open until it is done, because the identity to ask about
 * only exists during the handshake.
 */
@WebSocket(path = "/api/v1/notifications", endpointId = Notifications.ENDPOINT_ID)
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Notifications {

    public static final String ENDPOINT_ID = "keydra-notifications";

    private final CurrentVertxRequest request;
    private final SocketAudience audience;

    @Inject
    Notifications(CurrentVertxRequest request, SocketAudience audience) {
        this.request = request;
        this.audience = audience;
    }

    /**
     * Remembers which session opened this socket.
     *
     * <p>A socket is authorised when it is opened and then lives for as long as it lives, so a
     * session ended afterwards has to be able to find what is still open on it. This is how.
     */
    @OnOpen
    public Uni<Void> onOpen(WebSocketConnection connection) {
        String session = Sessions.presented(request.getCurrent());
        if (session != null) {
            connection.userData().put(SessionEndings.SESSION_KEY, session);
        }
        // Returned rather than started and forgotten: the socket counts as open when this
        // finishes, and nothing is pushed to one that is still opening. That is what removes the
        // window where an envelope could arrive before the answer to "may they see this" does.
        return audience.attach(connection);
    }

    /**
     * Discards anything a client sends.
     *
     * <p>Declared rather than omitted: without a handler the runtime has no answer for an inbound
     * frame, and silently ignoring one is the behaviour this endpoint wants.
     */
    @OnTextMessage
    public void onMessage(String message) {
        // Intentionally empty: this socket is server-to-client only.
    }
}
