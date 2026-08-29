package io.keydra.common.vertx;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refuses a WebSocket handshake that came from somewhere else.
 *
 * <p>The attack this closes is old enough to have a name — cross-site WebSocket hijacking — and it
 * works because a WebSocket handshake is not subject to the same-origin policy the way a fetch is.
 * A page anywhere can open a socket to this one, and the browser attaches the cookies for this
 * origin while it does. Whatever the socket then does, it does as whoever is signed in.
 *
 * <p>Keydra has three, and one of them runs commands against somebody's server. That is the whole
 * argument: a page on any site, open in a tab belonging to somebody signed in here, could have
 * spoken to it.
 *
 * <p>{@code SameSite=Lax} on the session cookie already withholds it from a handshake in a current
 * browser, because a handshake is a subresource request rather than a navigation. This is here
 * because that is one mitigation, in one place, that depends on the browser being current and on
 * nobody ever having a reason to relax the cookie. An Origin check is the rule itself.
 *
 * <p>What counts as the same origin is the configured public URL when there is one, plus whatever
 * else configuration names — a deployment where the frontend is served from a different host than
 * the API says so, once, in a property. With no public URL configured the request's own {@code
 * Host} is used, which is not a security decision so much as an admission: an instance that does
 * not know its own address cannot tell a foreign origin from its own, and refusing every socket
 * would make it useless rather than safe. Configure the address.
 *
 * <p>A handshake with no {@code Origin} at all is allowed. Browsers always send one, so a request
 * without it is not a browser — it is a command-line client or another service, neither of which
 * has ambient cookies to be abused. It still has to authenticate like everything else.
 */
@ApplicationScoped
public class SameOriginSockets {

    private static final Logger LOG = Logger.getLogger(SameOriginSockets.class);

    /** Before the route, because a refusal has to happen before the upgrade does. */
    private static final int BEFORE_THE_ROUTE = 400;

    private final Optional<String> publicUrl;
    private final List<String> alsoAllowed;
    private final boolean enabled;

    @Inject
    SameOriginSockets(
            @ConfigProperty(name = "keydra.public-url") Optional<String> publicUrl,
            @ConfigProperty(name = "keydra.security.socket-origins") Optional<List<String>> extra,
            @ConfigProperty(name = "keydra.security.socket-origin-check") boolean enabled) {
        this.publicUrl = publicUrl.filter(url -> !url.isBlank());
        this.alsoAllowed = extra.orElse(List.of());
        this.enabled = enabled;
    }

    void install(@Observes Filters filters) {
        if (enabled) {
            filters.register(this::check, BEFORE_THE_ROUTE);
        }
    }

    private void check(RoutingContext context) {
        if (!isHandshake(context) || allowed(context)) {
            context.next();
            return;
        }
        LOG.warnf(
                "Refused a WebSocket handshake for %s from origin %s",
                context.normalizedPath(), context.request().getHeader(HttpHeaders.ORIGIN));
        // Plain, and the same for every reason it could be refused. What is on the other end is
        // not a person reading an error.
        context.response().setStatusCode(403).end();
    }

    private static boolean isHandshake(RoutingContext context) {
        String upgrade = context.request().getHeader(HttpHeaders.UPGRADE);
        return upgrade != null && upgrade.equalsIgnoreCase("websocket");
    }

    private boolean allowed(RoutingContext context) {
        String origin = context.request().getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            // Not a browser. Nothing ambient to abuse; it still has to authenticate.
            return true;
        }
        return acceptable(context).contains(normalise(origin));
    }

    /** The origins this instance answers a socket for. */
    private Set<String> acceptable(RoutingContext context) {
        Set<String> origins = new LinkedHashSet<>();
        publicUrl.map(SameOriginSockets::normalise).ifPresent(origins::add);
        alsoAllowed.stream()
                .filter(one -> !one.isBlank())
                .map(SameOriginSockets::normalise)
                .forEach(origins::add);
        if (origins.isEmpty()) {
            String host =
                    context.request().authority() == null
                            ? null
                            : context.request().authority().toString();
            if (host != null) {
                origins.add(normalise((context.request().isSSL() ? "https://" : "http://") + host));
            }
        }
        return origins;
    }

    /** Scheme, host and port, lower case, no trailing slash — the shape an Origin header has. */
    private static String normalise(String url) {
        String trimmed = url.trim();
        try {
            URI parsed = URI.create(trimmed);
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                return trimmed.toLowerCase(java.util.Locale.ROOT);
            }
            String base =
                    parsed.getScheme().toLowerCase(java.util.Locale.ROOT)
                            + "://"
                            + parsed.getHost().toLowerCase(java.util.Locale.ROOT);
            return parsed.getPort() == -1 ? base : base + ":" + parsed.getPort();
        } catch (IllegalArgumentException unparseable) {
            return trimmed.toLowerCase(java.util.Locale.ROOT);
        }
    }
}
