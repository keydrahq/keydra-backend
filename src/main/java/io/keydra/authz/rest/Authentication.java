package io.keydra.authz.rest;

import io.keydra.authz.dto.AuthzDtos.AuthState;
import io.keydra.authz.dto.AuthzDtos.EffectivePermissions;
import io.keydra.authz.dto.AuthzDtos.SetupRequest;
import io.keydra.authz.dto.AuthzDtos.UserSummary;
import io.keydra.authz.service.AuthzAdminService;
import io.keydra.authz.service.EffectiveAccess;
import io.keydra.authz.service.LocalIdentities;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.FormAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Signing in, and finding out whether there is anything to sign into.
 *
 * <p>Open to anyone, necessarily. Every question here has to be answerable before the asker is
 * anybody: what this instance expects of them, how to become the first administrator of one that
 * has none, and — once they are somebody — what they may do, so the interface can stop offering
 * what would be refused.
 *
 * <p>Signing in itself is not here. Quarkus' form authentication handles it in the HTTP layer,
 * before a request reaches JAX-RS, so that a password is checked by the one piece of code written
 * for it and a session cookie is signed and encrypted by the framework rather than by this
 * application. Its path is configured in {@code application.properties} to sit under this one.
 * Signing out is here, because this Quarkus offers logout as a call rather than as a route.
 */
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Authentication", description = "Signing in and what the caller may do")
@PermitAll
public class Authentication {

    private final SecurityIdentity identity;
    private final SecuritySettings settings;
    private final AuthzAdminService service;
    private final EffectiveAccess access;
    private final io.keydra.authz.service.Sessions sessions;
    private final LocalIdentities identities;
    private final io.quarkus.vertx.http.runtime.CurrentVertxRequest request;

    @Inject
    Authentication(
            SecurityIdentity identity,
            SecuritySettings settings,
            AuthzAdminService service,
            EffectiveAccess access,
            io.keydra.authz.service.Sessions sessions,
            LocalIdentities identities,
            io.quarkus.vertx.http.runtime.CurrentVertxRequest request) {
        this.identity = identity;
        this.settings = settings;
        this.service = service;
        this.access = access;
        this.sessions = sessions;
        this.identities = identities;
        this.request = request;
    }

    @GET
    @Path("/state")
    @Operation(
            summary = "Whether there is anything to sign into, and whether anybody has",
            description =
                    "The login page asks this before drawing itself. An instance with enforcement"
                            + " off has no sign-in at all; one with enforcement on and no accounts"
                            + " needs its first administrator before it has one.")
    @APIResponse(responseCode = "200", description = "What this instance expects")
    public Uni<AuthState> state() {
        if (!settings.enabled()) {
            return Uni.createFrom().item(new AuthState(false, false, true, name(), false));
        }
        return service.hasAccounts()
                .map(
                        any ->
                                new AuthState(
                                        true,
                                        !any,
                                        !identity.isAnonymous(),
                                        name(),
                                        owesAFactor()));
    }

    /**
     * Whether this session may do nothing but pair an authenticator.
     *
     * <p>Read off the identity, which is where it was worked out. The server has already taken the
     * roles away; this only lets the browser say why.
     */
    private boolean owesAFactor() {
        return identity != null
                && Boolean.TRUE.equals(identity.getAttribute(LocalIdentities.OWES_A_FACTOR));
    }

    @POST
    @Path("/setup")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create the first administrator",
            description =
                    "Accepted only while Keydra has no accounts at all. After that it is a closed"
                            + " door rather than a guarded one, which is what makes an open"
                            + " endpoint that creates an administrator safe.")
    @APIResponse(responseCode = "201", description = "The administrator was created")
    @APIResponse(responseCode = "409", description = "Keydra already has accounts")
    public Uni<RestResponse<UserSummary>> setup(@Valid SetupRequest request) {
        return service.createFirstAdministrator(request)
                .map(created -> RestResponse.status(Status.CREATED, created));
    }

    @POST
    @Path("/logout")
    @Operation(
            summary = "End this session",
            description =
                    "Clears the session cookie. A call rather than a configured path, because that"
                            + " is the shape this Quarkus offers logout in — and a property naming"
                            + " a path it does not read would have looked configured while doing"
                            + " nothing.")
    @APIResponse(responseCode = "204", description = "The session is over")
    public Uni<RestResponse<Void>> logout() {
        var http = request.getCurrent();
        String presented = io.keydra.authz.service.Sessions.presented(http);

        if (settings.enabled() && !identity.isAnonymous()) {
            FormAuthenticationMechanism.logout(identity);
        }
        sessions.clearCookie(http);

        // The row is ended as well as the cookie cleared. Clearing a cookie asks a browser to
        // forget something; ending the row means a copy of that cookie taken beforehand stops
        // working too, which is the difference this phase is about.
        if (presented == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            // Nothing to end, and saying so is not an error: a client that signs out of an
            // instance which never signed it in has got what it asked for.
            return Uni.createFrom().item(RestResponse.noContent());
        }
        return identities
                .userIdOf(identity.getPrincipal().getName())
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(false)
                                        : sessions.end(presented, userId))
                .replaceWith(RestResponse.noContent());
    }

    @GET
    @Path("/permissions")
    @Operation(
            summary = "What the caller may do, per target",
            description =
                    "So the interface can stop offering actions that would be refused. Resolved"
                            + " server-side for every visible target at once, because asking per"
                            + " button would be a request per button.")
    @APIResponse(responseCode = "200", description = "The caller's permissions")
    public Uni<EffectivePermissions> permissions() {
        return access.permissions();
    }

    private String name() {
        return identity.isAnonymous() || identity.getPrincipal() == null
                ? "anonymous"
                : identity.getPrincipal().getName();
    }
}
