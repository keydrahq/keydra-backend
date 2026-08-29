package io.keydra.authz.rest;

import io.keydra.authz.dto.ProviderDtos.SignInOption;
import io.keydra.authz.entity.IdentityProviderConfig;
import io.keydra.authz.exception.SignInFailedException;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.authz.service.ClientOrigin;
import io.keydra.authz.service.ExternalAccounts;
import io.keydra.authz.service.ProviderAdminService;
import io.keydra.authz.service.ProviderSignIn;
import io.keydra.authz.service.PublicUrl;
import io.keydra.authz.service.SessionIssuer;
import io.keydra.authz.service.SignInLog;
import io.keydra.common.vertx.OwnContext;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Signing in somewhere else and coming back.
 *
 * <p>Open, necessarily: every step happens before Keydra knows who anybody is. What stands in for
 * authentication until the last one is the flow's own state — a value this application invented,
 * kept in a cookie only it can read, and required to come back unchanged. Without that, anyone
 * could send somebody's browser to the callback with a code of their own and have them signed in as
 * an account they do not own.
 */
@Path("/api/v1/auth/providers")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sign-in providers", description = "Signing in through an external provider")
@PermitAll
public class SignIn {

    private static final Logger LOG = Logger.getLogger(SignIn.class);

    /**
     * Where the flow's own secrets live between the two requests.
     *
     * <p>{@code SameSite=Lax} rather than {@code Strict}: the callback arrives as a navigation from
     * the provider's site, and a strict cookie would not be sent with it — which would make every
     * sign-in look like a forged one.
     */
    private static final String FLOW_COOKIE = "keydra_signin";

    /**
     * Long enough to type a password and answer a second factor, short enough to be one attempt.
     */
    private static final int FLOW_SECONDS = 600;

    private final AuthzRepository repository;
    private final ProviderAdminService providers;
    private final ProviderSignIn flow;
    private final ExternalAccounts accounts;
    private final SessionIssuer sessions;
    private final PublicUrl publicUrl;
    private final CurrentVertxRequest request;
    private final SignInLog log;
    private final Vertx vertx;

    @Inject
    SignIn(
            AuthzRepository repository,
            ProviderAdminService providers,
            ProviderSignIn flow,
            ExternalAccounts accounts,
            SessionIssuer sessions,
            PublicUrl publicUrl,
            CurrentVertxRequest request,
            SignInLog log,
            Vertx vertx) {
        this.repository = repository;
        this.providers = providers;
        this.flow = flow;
        this.accounts = accounts;
        this.sessions = sessions;
        this.publicUrl = publicUrl;
        this.request = request;
        this.log = log;
        this.vertx = vertx;
    }

    @GET
    @Operation(
            summary = "The ways in this instance offers",
            description =
                    "Only the ones switched on and able to complete a flow. A button that leads"
                            + " nowhere is worse than no button: whoever presses it cannot tell"
                            + " that the fault is not theirs.")
    @APIResponse(responseCode = "200", description = "The providers offered")
    public Uni<List<SignInOption>> options() {
        return providers.options();
    }

    @GET
    @Path("/{key}/start")
    @Operation(
            summary = "Begin signing in through a provider",
            description =
                    "Answers with a redirect to the provider and a short-lived cookie holding this"
                            + " flow's state and its PKCE verifier.")
    @APIResponse(responseCode = "303", description = "Off to the provider")
    @APIResponse(responseCode = "404", description = "No such provider, or it is switched off")
    @WithSession
    public Uni<RestResponse<Void>> start(@PathParam("key") String key, @Context UriInfo uriInfo) {
        String home = publicUrl.of(uriInfo);

        return repository
                .providerByKey(key)
                .map(
                        provider -> {
                            if (provider == null || !provider.enabled) {
                                return failed(home, "There is no such way in.");
                            }
                            try {
                                return begin(provider, home);
                            } catch (SignInFailedException misconfigured) {
                                LOG.warnf(
                                        "Could not start a sign-in through %s: %s",
                                        key, misconfigured.getMessage());
                                return failed(home, misconfigured.getMessage());
                            }
                        });
    }

    private RestResponse<Void> begin(IdentityProviderConfig provider, String home) {
        String state = flow.secret();
        String verifier = flow.secret();
        String redirectUri = ProviderAdminService.redirectUri(home, provider.key);

        return RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.SEE_OTHER)
                .location(URI.create(flow.authorizationUrl(provider, redirectUri, state, verifier)))
                .cookie(flowCookie(state + ":" + verifier + ":" + provider.key, FLOW_SECONDS))
                .build();
    }

    @GET
    @Path("/{key}/callback")
    @Operation(
            summary = "Where the provider sends people back to",
            description =
                    "Checks the state, swaps the code for a token, finds or creates the account,"
                            + " brings its groups up to date and issues the same session cookie a"
                            + " password would have.")
    @APIResponse(responseCode = "303", description = "Signed in, or back to the login page")
    // On the endpoint rather than on the method that reads the provider: a call from here into
    // another method of this same class is not intercepted, so an annotation there would open
    // no session at all and the first query would fail.
    @WithSession
    public Uni<RestResponse<Void>> callback(
            @PathParam("key") String key,
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String error,
            @QueryParam("error_description") String errorDescription,
            @CookieParam(FLOW_COOKIE) String flowCookie,
            @Context UriInfo uriInfo) {

        String home = publicUrl.of(uriInfo);

        // Taken now, while this is still the request's own thread. Everything that writes a
        // cookie writes it here: RESTEasy and Vert.x both set Set-Cookie on the same response,
        // and the one that goes last wins, so this endpoint uses one of them for both.
        RoutingContext context = request.getCurrent();
        clearFlowCookie(context);

        if (error != null) {
            // The provider turned them down — cancelled the consent screen, usually.
            return Uni.createFrom()
                    .item(failed(home, errorDescription == null ? error : errorDescription));
        }

        Flow expected = Flow.parse(flowCookie);
        if (expected == null || !expected.providerKey().equals(key) || code == null) {
            return Uni.createFrom()
                    .item(failed(home, "That sign-in took too long. Please try again."));
        }
        if (state == null || !constantTimeEquals(expected.state(), state)) {
            // The one check that stands between this endpoint and signing somebody into an
            // account they do not own.
            LOG.warnf("Refused a callback for %s whose state did not match", key);
            return Uni.createFrom().item(failed(home, "That sign-in could not be verified."));
        }

        return complete(key, code, expected.verifier(), home, context)
                .onFailure(SignInFailedException.class)
                .recoverWithItem(failure -> failed(home, failure.getMessage()))
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.error("A sign-in failed unexpectedly", failure);
                            return failed(home, "Something went wrong signing you in.");
                        });
    }

    private Uni<RestResponse<Void>> complete(
            String key,
            String code,
            String verifier,
            String home,
            io.vertx.ext.web.RoutingContext context) {
        return repository
                .providerByKey(key)
                .flatMap(
                        provider -> {
                            if (provider == null || !provider.enabled) {
                                return Uni.createFrom()
                                        .failure(
                                                new SignInFailedException(
                                                        "There is no such way in."));
                            }
                            String redirectUri =
                                    ProviderAdminService.redirectUri(home, provider.key);
                            return flow.identify(provider, code, redirectUri, verifier)
                                    .flatMap(claims -> accounts.accept(provider, claims))
                                    .map(username -> signedIn(username, home, context));
                        });
    }

    /**
     * Issues the session and sends them to the application.
     *
     * <p>The cookie is written straight onto the Vert.x response rather than built here, because it
     * is Quarkus' own login manager that writes it — the same one the password form uses, so a
     * session is a session however it was arrived at.
     */
    private RestResponse<Void> signedIn(String username, String home, RoutingContext context) {
        sessions.issue(username, context);
        // Written down like a password sign-in is, and compared against the same history. A
        // provider vouching for somebody says the password was right somewhere else; it says
        // nothing about whether this is the same person who signed in from here last week.
        OwnContext.run(
                vertx,
                () -> log.succeeded(username, ClientOrigin.of(context), providerKey(context)),
                unwritable -> LOG.debugf(unwritable, "Could not record a provider sign-in"));
        return seeOther(home + "/");
    }

    /** Which way in this was, taken from the path so the record says more than "not a password". */
    private static String providerKey(RoutingContext context) {
        String key = context == null ? null : context.pathParam("key");
        return key == null || key.isBlank() ? "provider" : key;
    }

    /** Back to the login page, with something for it to say. */
    private static RestResponse<Void> failed(String home, String message) {
        return seeOther(
                home
                        + "/?signInError="
                        + URLEncoder.encode(
                                message == null ? "Sign-in failed" : message,
                                StandardCharsets.UTF_8));
    }

    private static RestResponse<Void> seeOther(String where) {
        return RestResponse.ResponseBuilder.<Void>create(RestResponse.Status.SEE_OTHER)
                .location(URI.create(where))
                .build();
    }

    /** The flow is over either way, so its secrets go whether it worked or not. */
    private static void clearFlowCookie(RoutingContext context) {
        context.response()
                .addCookie(
                        Cookie.cookie(FLOW_COOKIE, "")
                                .setPath("/api/v1/auth")
                                .setHttpOnly(true)
                                .setSameSite(CookieSameSite.LAX)
                                .setMaxAge(0));
    }

    private static NewCookie flowCookie(String value, int maxAge) {
        return new NewCookie.Builder(FLOW_COOKIE)
                .value(value)
                .path("/api/v1/auth")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(maxAge)
                .build();
    }

    /** What the cookie carries between the two halves of a flow. */
    private record Flow(String state, String verifier, String providerKey) {

        static Flow parse(String cookie) {
            if (cookie == null || cookie.isBlank()) {
                return null;
            }
            String[] parts = cookie.split(":", 3);
            return parts.length == 3 ? new Flow(parts[0], parts[1], parts[2]) : null;
        }
    }

    /**
     * Compared without returning early, like a password.
     *
     * <p>The state is a secret for the length of one sign-in, and a comparison that stops at the
     * first wrong character tells anybody timing it how much of their guess was right.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
