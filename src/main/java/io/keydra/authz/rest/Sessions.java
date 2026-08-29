package io.keydra.authz.rest;

import io.keydra.authz.dto.SessionSummary;
import io.keydra.authz.service.LocalIdentities;
import io.keydra.security.Audited;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The browsers you are signed in on.
 *
 * <p>Yours, and only yours. There is no endpoint here for reading somebody else's: what a person's
 * sessions say is where they work and when they work, and an administrator who needs to act on a
 * compromised account can end its sessions without reading them. Somebody's movements are not an
 * administrative view.
 *
 * <p>Every path is about the caller, so nothing takes a user id. An endpoint that took one would be
 * an endpoint somebody could pass a different one to.
 *
 * <p>Signed in is the only requirement, deliberately — not a role. An account with no grants at all
 * can still be signed in on a laptop somebody left in a café, and managing your own safety is not
 * something to be permitted. It is the one thing in Keydra that everybody may do.
 */
@Path("/api/v1/auth/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sessions", description = "The browsers you are signed in on")
@Authenticated
public class Sessions {

    private final io.keydra.authz.service.Sessions sessions;
    private final LocalIdentities identities;
    private final SecurityIdentity identity;
    private final CurrentVertxRequest request;

    @Inject
    Sessions(
            io.keydra.authz.service.Sessions sessions,
            LocalIdentities identities,
            SecurityIdentity identity,
            CurrentVertxRequest request) {
        this.sessions = sessions;
        this.identities = identities;
        this.identity = identity;
        this.request = request;
    }

    /**
     * A page of them, capped whether or not anybody asked for a cap.
     *
     * <p>A reply that grows with how long an account has existed is worth bounding even while
     * nothing is calling it, and a default that caps it is the difference between a bound and a
     * suggestion. There is no count beside it: a caller pages until it gets back fewer rows than it
     * asked for, which is what a list endpoint needs and no more.
     */
    @GET
    @Operation(
            summary = "The browsers you are signed in on",
            description =
                    "The one reading this first, then newest first, a page at a time. Sessions that"
                            + " have been ended or have lapsed are not listed: the question this"
                            + " answers is which browsers can act as you now.")
    @APIResponse(responseCode = "200", description = "Your sessions")
    public Uni<List<SessionSummary>> list(
            @QueryParam("first") @DefaultValue("20") int first,
            @QueryParam("offset") @DefaultValue("0") int offset) {
        String current = io.keydra.authz.service.Sessions.presented(request.getCurrent());
        return currentUserId().flatMap(userId -> sessions.mine(userId, current, first, offset));
    }

    @DELETE
    @Path("/{id}")
    @Audited("session.end")
    @Operation(
            summary = "End one of your sessions",
            description =
                    "Takes effect on that browser's next request rather than at its own expiry."
                            + " Ending the session you are reading this on signs you out.")
    @APIResponse(responseCode = "204", description = "It is ended")
    @APIResponse(responseCode = "404", description = "No session of yours with that id")
    public Uni<RestResponse<Void>> end(@PathParam("id") String id) {
        return currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(false)
                                        : sessions.end(id, userId))
                .map(
                        ended ->
                                ended
                                        ? RestResponse.<Void>status(Status.NO_CONTENT)
                                        : RestResponse.<Void>status(Status.NOT_FOUND));
    }

    @DELETE
    @Audited("session.end-others")
    @Operation(
            summary = "End every session except this one",
            description =
                    "What to press when something looks wrong. The session reading this is kept,"
                            + " because answering a click about safety by signing somebody out is"
                            + " answering it with the opposite of what it asked for.")
    @APIResponse(responseCode = "200", description = "How many were ended")
    public Uni<Integer> endOthers() {
        String current = io.keydra.authz.service.Sessions.presented(request.getCurrent());
        return currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(0)
                                        : sessions.endOthers(userId, current));
    }

    /** The account asking, or null when nobody is. */
    private Uni<Long> currentUserId() {
        if (identity.isAnonymous() || identity.getPrincipal() == null) {
            return Uni.createFrom().nullItem();
        }
        return identities.userIdOf(identity.getPrincipal().getName());
    }
}
