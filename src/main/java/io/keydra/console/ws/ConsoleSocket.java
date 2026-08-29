package io.keydra.console.ws;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.SessionEndings;
import io.keydra.authz.service.Sessions;
import io.keydra.console.dto.ConsoleCommand;
import io.keydra.console.dto.ConsoleResult;
import io.keydra.console.service.ConsoleService;
import io.keydra.security.Roles;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * An interactive session against one target.
 *
 * <p>A socket rather than a series of POSTs because a console is a conversation: the client sends
 * lines as they are typed and reads replies as they arrive, and a result carries the id of the
 * command that produced it so a fast reply cannot be mistaken for a slow one's.
 *
 * <p>One socket per connection profile, addressed by the path. Nothing about the session is held
 * here — the target's client lives in the pool, and the history in the database — so a dropped
 * socket costs the transcript on screen and nothing else.
 *
 * <p>Guarded exactly as the REST half of this feature is, and it has to be: a socket that runs
 * commands is the most powerful thing in the application, and the transport it arrives over changes
 * nothing about who may use it. The role is the coarse gate; the permission is asked per target, so
 * a console on one server is not a console on all of them.
 */
@WebSocket(path = "/api/v1/connections/{connectionId}/console")
@RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
public class ConsoleSocket {

    private final ConsoleService service;
    private final CurrentVertxRequest request;

    @Inject
    ConsoleSocket(ConsoleService service, CurrentVertxRequest request) {
        this.service = service;
        this.request = request;
    }

    /**
     * Remembers which session opened this console.
     *
     * <p>The socket that most needs it. Authorisation happens when it opens and then it carries
     * commands for as long as it lives, so a session ended while a console is open would otherwise
     * leave somebody running commands on a session that no longer exists.
     */
    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        String session = Sessions.presented(request.getCurrent());
        if (session != null) {
            connection.userData().put(SessionEndings.SESSION_KEY, session);
        }
    }

    /**
     * Runs one line and answers with its result.
     *
     * <p>Returning the result, rather than broadcasting it, keeps the reply on the socket that
     * asked: two people with the console open on the same target should not see each other's
     * commands appear in their transcripts.
     */
    @OnTextMessage
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<ConsoleResult> onCommand(
            @PathParam("connectionId") String connectionId, ConsoleCommand command) {
        return service.run(Long.valueOf(connectionId), command);
    }
}
