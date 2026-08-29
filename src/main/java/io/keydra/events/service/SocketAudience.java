package io.keydra.events.service;

import io.keydra.authz.service.CallerPermissions;
import io.keydra.authz.service.PermissionResolver;
import io.keydra.common.vertx.OwnContext;
import io.keydra.connections.service.ConnectionService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Which targets a socket is allowed to hear about.
 *
 * <p>The notification hub used to push every envelope to every open socket, having checked only
 * that somebody was signed in. What goes out on it is the name of a target, the keys that changed
 * on it, a reading from it and the text of an alert about it — so an account with a grant on one
 * target was being sent a running description of every other one. Every other surface in Keydra
 * filters by grant; this was the one place the model had a hole in it, and it had it because a
 * broadcast has no caller to ask about.
 *
 * <p>So the answer is worked out while there is still a caller to ask — during the handshake, where
 * the identity is present and a session can be opened — and then kept on the socket. Asking per
 * envelope instead would be a database round trip for every sample of every target for every open
 * browser: a cost paid every few seconds forever, to answer a question that changes a few times a
 * year.
 *
 * <p>Kept, and therefore kept up to date. Two things change the answer after a socket has opened:
 * somebody's access changes, and a target is created or removed. Both call {@link #refreshAll()},
 * which is why the socket remembers *who* it belongs to rather than only what they could see. The
 * request that carried the identity is gone by then, so an id and one flag are stored at the
 * handshake and the recomputation runs from those.
 *
 * <p>Fail closed. A socket whose audience could not be worked out has none, and receives only the
 * envelopes that name no target.
 */
@ApplicationScoped
public class SocketAudience {

    private static final Logger LOG = Logger.getLogger(SocketAudience.class);

    /** The targets this socket may hear about. Absent means none, which is where it starts. */
    public static final UserData.TypedKey<Set<Long>> AUDIENCE =
            new UserData.TypedKey<>("keydra.audience");

    /** Whose socket this is, so the answer can be worked out again later. Null when nobody's. */
    private static final UserData.TypedKey<Long> OWNER = new UserData.TypedKey<>("keydra.owner");

    /**
     * Whether this socket hears about everything regardless of grants.
     *
     * <p>True where enforcement is off, and where the roles came from a token — which is what a
     * built-in role has always meant here.
     *
     * <p>A flag rather than a set holding every id, and the difference is that this one does not go
     * stale. "Sees everything" stays true when a target is created a minute later; "sees these
     * five" quietly stops being the answer. Only a socket whose access comes from grants carries a
     * set, which is also the only kind that ever needed one.
     */
    private static final UserData.TypedKey<Boolean> UNFILTERED =
            new UserData.TypedKey<>("keydra.unfiltered");

    private final ConnectionService connections;
    private final CallerPermissions caller;
    private final PermissionResolver resolver;
    private final OpenConnections open;
    private final Vertx vertx;

    @Inject
    SocketAudience(
            ConnectionService connections,
            CallerPermissions caller,
            PermissionResolver resolver,
            OpenConnections open,
            Vertx vertx) {
        this.connections = connections;
        this.caller = caller;
        this.resolver = resolver;
        this.open = open;
        this.vertx = vertx;
    }

    /**
     * Works out what this socket may hear about, and remembers it along with whose it is.
     *
     * <p>Returned as a {@code Uni} so the endpoint's {@code @OnOpen} can hand it back and the
     * socket is not open until it has finished. Nothing is sent to a socket that is still opening,
     * so there is no window in which an envelope arrives before the answer does.
     *
     * <p>The list it asks for is the same one the connections page asks for, filtered the same way.
     * That is deliberate rather than convenient — a second implementation of "what may this person
     * see" is a second thing to get wrong, and the two would drift the first time either changed.
     */
    @WithSession
    public Uni<Void> attach(WebSocketConnection connection) {
        boolean unfiltered = !caller.enforcing() || caller.holdsRoleClaims();
        connection.userData().put(UNFILTERED, unfiltered);
        if (unfiltered) {
            // Nothing to work out and nothing to keep up to date. Skipping the query is the small
            // part; not holding a list that stops being true is the point.
            return Uni.createFrom().voidItem();
        }
        return caller.currentUserId()
                .invoke(userId -> connection.userData().put(OWNER, userId))
                .flatMap(ignored -> connections.list())
                .map(profiles -> profiles.stream().map(one -> one.id()).collect(Collectors.toSet()))
                .invoke(targets -> connection.userData().put(AUDIENCE, targets))
                .replaceWithVoid()
                .onFailure()
                .recoverWithItem(
                        unanswerable -> {
                            // Opening still succeeds. What the socket does not get is anything
                            // that names a target, which is the safe half of the traffic.
                            LOG.warnf(
                                    unanswerable,
                                    "Could not work out what socket %s may hear about; it will"
                                            + " receive only what names no target",
                                    connection.id());
                            return null;
                        });
    }

    /** Whether this envelope may go to this socket. */
    public boolean maySee(WebSocketConnection connection, Long connectionId) {
        if (connectionId == null) {
            // News about Keydra rather than about any one target. Everybody signed in may have it.
            return true;
        }
        if (Boolean.TRUE.equals(connection.userData().get(UNFILTERED))) {
            return true;
        }
        Set<Long> audience = connection.userData().get(AUDIENCE);
        return audience != null && audience.contains(connectionId);
    }

    /**
     * Works every open socket's answer out again.
     *
     * <p>Called when something changed who may see what — a grant, a group, an account — and when
     * the set of targets itself changed. Both matter and for opposite reasons: without the first, a
     * revocation would not reach a socket until its browser was reloaded, quietly turning "takes
     * effect on the next request" into "takes effect tomorrow". Without the second, a target
     * created after somebody opened a page would be silent on it for as long as it stayed open.
     *
     * <p>Nobody waits for this. It runs on a context of its own because it is started from the end
     * of a transaction whose session is closing, and a socket that is a moment behind hears one
     * envelope late rather than the wrong ones.
     */
    public void refreshAll() {
        List<WebSocketConnection> sockets = open.stream().toList();
        if (sockets.isEmpty()) {
            return;
        }
        OwnContext.run(
                vertx,
                () -> recompute(sockets),
                failure ->
                        LOG.debug("Could not refresh what open sockets may hear about", failure));
    }

    /**
     * One query for every id, then one resolution per socket.
     *
     * <p>Sequential rather than concurrent, which is the rule wherever a reactive session is
     * involved: it runs one query at a time, and issuing them at once produces "session is
     * currently executing another query" rather than a faster answer.
     */
    @WithSession
    Uni<Void> recompute(List<WebSocketConnection> sockets) {
        return connections
                .everyId()
                .flatMap(
                        every -> {
                            Uni<Void> chain = Uni.createFrom().voidItem();
                            for (WebSocketConnection socket : sockets) {
                                chain = chain.flatMap(ignored -> recomputeOne(socket, every));
                            }
                            return chain;
                        });
    }

    private Uni<Void> recomputeOne(WebSocketConnection socket, List<Long> every) {
        if (Boolean.TRUE.equals(socket.userData().get(UNFILTERED))) {
            // Already true of everything, including whatever has just been created.
            return Uni.createFrom().voidItem();
        }
        Long owner = socket.userData().get(OWNER);
        if (owner == null) {
            // Nobody Keydra has an account for. Nothing to resolve, and nothing to hear.
            socket.userData().put(AUDIENCE, Set.of());
            return Uni.createFrom().voidItem();
        }
        return resolver.visibleConnections(owner, every)
                .invoke(visible -> socket.userData().put(AUDIENCE, visible))
                .replaceWithVoid();
    }
}
