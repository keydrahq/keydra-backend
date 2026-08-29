package io.keydra.common.vertx;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Marks the framework's session cookie for TLS on the way out.
 *
 * <p>Because it cannot be configured. Quarkus' form authentication writes that cookie itself, and
 * its configuration has a name, a path, a domain, a max age, SameSite and HttpOnly — and no Secure.
 * What it uses instead is whether the request it is answering arrived over TLS, which behind a
 * proxy that terminates TLS is false. That is the normal way to run this, and it is exactly the
 * deployment where the flag matters.
 *
 * <p>Setting {@code quarkus.http.proxy.proxy-address-forwarding} fixes it properly, by making the
 * request tell the truth about its scheme. This is here because that switch is off unless somebody
 * turns it on, and a session cookie without Secure is not a thing to leave depending on a second
 * setting nobody was told about. Both together are the belt and the braces.
 *
 * <p>Rewriting a header rather than issuing the cookie ourselves. Taking the login endpoint back
 * from the framework is the real answer and is a change of its own; this adds one attribute to a
 * cookie already correct in every other respect.
 */
@ApplicationScoped
public class SecureSessionCookie {

    /** After everything else: the cookie has to exist before an attribute can be added to it. */
    private static final int AFTER_THE_ROUTE = -100;

    private final boolean secure;
    private final String cookieName;

    @Inject
    SecureSessionCookie(
            @ConfigProperty(name = "keydra.security.cookie-secure") boolean secure,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-name") String cookieName) {
        this.secure = secure;
        this.cookieName = cookieName;
    }

    void install(@Observes Filters filters) {
        if (!secure) {
            return;
        }
        filters.register(
                context -> {
                    context.addHeadersEndHandler(ignored -> mark(context));
                    context.next();
                },
                AFTER_THE_ROUTE);
    }

    /**
     * Adds {@code Secure} to the session cookie, and to nothing else.
     *
     * <p>At headers-end, because the cookie is written while the response is being built and is not
     * there when a filter first runs. Matched by name: a response can carry more than one
     * Set-Cookie and the others are already correct — {@code keydra_sid} sets its own flag, and the
     * sign-in flow's temporary cookie is cleared within the request that made it.
     */
    private void mark(io.vertx.ext.web.RoutingContext context) {
        List<String> cookies = context.response().headers().getAll(HttpHeaders.SET_COOKIE);
        if (cookies.isEmpty()) {
            return;
        }
        List<String> marked =
                cookies.stream()
                        .map(
                                cookie ->
                                        cookie.startsWith(cookieName + "=")
                                                        && !cookie.contains("; Secure")
                                                ? cookie + "; Secure"
                                                : cookie)
                        .toList();
        if (!marked.equals(cookies)) {
            context.response().headers().remove(HttpHeaders.SET_COOKIE);
            marked.forEach(
                    cookie -> context.response().headers().add(HttpHeaders.SET_COOKIE, cookie));
        }
    }
}
