package io.keydra.authz.service;

import io.quarkus.vertx.http.runtime.security.PersistentLoginManager;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Issues the session cookie for somebody who signed in somewhere other than the login form.
 *
 * <p>The same cookie, deliberately. Keydra has one notion of a session — a name, an expiry, signed
 * and encrypted — and an external provider is a different way of arriving at it, not a different
 * kind of session. Two formats would mean two things to expire, two to revoke and two to get wrong;
 * this builds Quarkus' own login manager so that the cookie a provider issues is indistinguishable
 * from the one a password issues, and {@link SessionIdentityProvider} restores both without knowing
 * which is which.
 *
 * <p>Every setting is read from the same property the framework reads, and every one of them is
 * written out explicitly in {@code application.properties} rather than left to a default. That is
 * not tidiness: {@code FormAuthConfig} is not a bean and cannot be injected, so these two readers
 * of the same configuration are only guaranteed to agree while there is nothing for them to
 * disagree about.
 */
@ApplicationScoped
public class SessionIssuer {

    private final String encryptionKey;
    private final String cookieName;
    private final Duration timeout;
    private final Duration newCookieInterval;
    private final boolean httpOnly;
    private final String sameSite;
    private final String path;
    private final Optional<Duration> maxAge;
    private final Optional<String> domain;
    private final boolean cookieSecure;

    private PersistentLoginManager sessions;

    @Inject
    SessionIssuer(
            @ConfigProperty(name = "quarkus.http.auth.session.encryption-key") String encryptionKey,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-name") String cookieName,
            @ConfigProperty(name = "quarkus.http.auth.form.timeout") Duration timeout,
            @ConfigProperty(name = "quarkus.http.auth.form.new-cookie-interval")
                    Duration newCookieInterval,
            @ConfigProperty(name = "quarkus.http.auth.form.http-only-cookie") boolean httpOnly,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-same-site") String sameSite,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-path") String path,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-max-age")
                    Optional<Duration> maxAge,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-domain") Optional<String> domain,
            @ConfigProperty(name = "keydra.security.cookie-secure") boolean cookieSecure) {
        this.encryptionKey = encryptionKey;
        this.cookieName = cookieName;
        this.timeout = timeout;
        this.newCookieInterval = newCookieInterval;
        this.httpOnly = httpOnly;
        this.sameSite = sameSite;
        this.path = path;
        this.maxAge = maxAge;
        this.domain = domain;
        this.cookieSecure = cookieSecure;
    }

    @PostConstruct
    void build() {
        sessions =
                new PersistentLoginManager(
                        encryptionKey,
                        cookieName,
                        timeout.toMillis(),
                        newCookieInterval.toMillis(),
                        httpOnly,
                        // Quarkus reads this case-insensitively into an enum; the login manager
                        // takes the enum's own name.
                        sameSite.toUpperCase(java.util.Locale.ROOT),
                        path,
                        maxAge.map(Duration::toSeconds).orElse(-1L),
                        domain.orElse(null));
    }

    /**
     * Signs this person in.
     *
     * <p>Whether the cookie is marked for TLS only is read from configuration and not passed in. It
     * used to be the caller's answer to "did this request arrive over TLS", which is the wrong
     * question behind a proxy that terminates TLS somewhere else — there the answer is no, on the
     * deployments where the flag matters most.
     */
    public void issue(String username, RoutingContext context) {
        sessions.save(username, context, cookieName, null, cookieSecure);
    }
}
