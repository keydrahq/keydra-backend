package io.keydra.authz.service;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restores the identity carried by a session cookie.
 *
 * <p>The cookie is signed and encrypted by Quarkus and only holds a name, so every request rebuilds
 * the roles from the grants as they are now. That is deliberate: a session that carried its own
 * copy of them would keep whatever it was given at sign-in, and revoking somebody's access would
 * not take effect until they logged out.
 *
 * <p>For the same reason a disabled or deleted account fails here rather than being tolerated — the
 * cookie is proof of a past sign-in, not of a present account.
 *
 * <p>Since phase 24 it is also proof of a session, and the session is a row. The framework's cookie
 * says which account; a second cookie says which sign-in, and a sign-in that has been ended stops
 * working here rather than at its own expiry. A request arriving with the first cookie and not the
 * second has just signed in — form authentication has written its cookie and nothing has started a
 * session yet — so that is where one begins.
 */
@ApplicationScoped
public class SessionIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    /**
     * How stale "last seen" is allowed to get.
     *
     * <p>Every request with a cookie arrives here, so writing the timestamp each time would put a
     * transaction in front of every call to record something nobody reads to the minute. Five
     * minutes is precise enough to answer "is this account still in use".
     */
    private static final Duration INTERVAL = Duration.ofMinutes(5);

    private final LocalIdentities identities;
    private final Sessions sessions;

    /** When each account was last written, so the write happens on few requests rather than all. */
    private final Map<String, Instant> lastWritten = new ConcurrentHashMap<>();

    @Inject
    SessionIdentityProvider(LocalIdentities identities, Sessions sessions) {
        this.identities = identities;
        this.sessions = sessions;
    }

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            TrustedAuthenticationRequest trusted, AuthenticationRequestContext context) {
        String username = trusted.getPrincipal();
        // Taken from the authentication request rather than from the request-scoped bean:
        // authentication runs before that scope is active, and asking for it there fails with
        // "RequestScoped context was not active" — on every signed-in request.
        RoutingContext http = HttpSecurityUtils.getRoutingContextAttribute(trusted);
        String presented = Sessions.presented(http);

        return identities
                .identityOf(username)
                .onItem()
                .ifNull()
                .failWith(() -> new AuthenticationFailedException("That account is gone"))
                .flatMap(identity -> withSession(identity, username, presented, http))
                .call(ignored -> noteSeen(username));
    }

    /**
     * Checks the session behind the cookie, or starts one.
     *
     * <p>Three cases, and only the first is a refusal. A presented session that is live goes
     * through. A presented session that has been ended — or has lapsed, or was never ours — fails,
     * which is the whole point: ending a session has to take effect on the next request rather than
     * at its own expiry. And a request with no session cookie at all is somebody who has just
     * signed in, so one begins here; that also covers the sessions issued before this existed,
     * which get a row on their next request rather than being thrown out.
     *
     * <p>That last case is also this arrangement's limit, and it is worth being plain about. The
     * framework's own form authentication writes its cookie before anything here runs, so the first
     * request after a sign-in cannot already name a session — which means a request holding only
     * that cookie is given a new one rather than refused. A browser always holds both, so ending a
     * session ends it; what this does not stop is somebody who obtained the framework's cookie
     * alone. Closing that means Keydra issuing both cookies itself, at sign-in, which means taking
     * the login endpoint back from the framework — a change worth making on its own rather than
     * folded into this one.
     */
    private Uni<SecurityIdentity> withSession(
            SecurityIdentity identity, String username, String presented, RoutingContext http) {
        if (presented != null) {
            return sessions.isLive(presented)
                    .flatMap(
                            live -> {
                                if (live) {
                                    return Uni.createFrom().item(identity);
                                }
                                // Both cookies, not just ours. The framework's still says this
                                // browser signed in, and a browser that keeps it is refused on
                                // every request without ever being shown the way back to the
                                // sign-in page.
                                sessions.clearBothCookies(http);
                                return Uni.createFrom()
                                        .failure(
                                                new AuthenticationFailedException(
                                                        "That session has ended"));
                            });
        }
        return identities
                .userIdOf(username)
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(identity)
                                        : sessions.begin(userId, http).replaceWith(identity));
    }

    private Uni<Void> noteSeen(String username) {
        Instant now = Instant.now();
        Instant last = lastWritten.get(username);
        if (last != null && last.isAfter(now.minus(INTERVAL))) {
            return Uni.createFrom().voidItem();
        }
        lastWritten.put(username, now);
        return identities.recordSeen(username, now);
    }
}
