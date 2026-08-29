package io.keydra.authz.service;

import io.keydra.authz.exception.PublicUrlNotConfiguredException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Optional;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where a browser reaches this instance.
 *
 * <p>Needed because a redirect URI is agreed with a provider in advance and has to match to the
 * character, and the address Keydra sees is often not the address anybody types: a reverse proxy
 * terminates TLS on another host, and in development the browser talks to a Vite server on another
 * port that forwards {@code /api} here. Both cases end with a provider refusing a sign-in because
 * the redirect URI was {@code http://localhost:8181/...} and it was told {@code
 * https://keydra.example}.
 *
 * <p>Configured where it differs, derived from the request where it does not — and the second half
 * of that is only true in development.
 *
 * <p>What a request says about where it arrived is the {@code Host} header, which is a thing the
 * client writes. Deriving from it means a redirect URI, and the address somebody is sent to after
 * signing in, are both chosen by whoever sent the request: a sign-in that completes correctly and
 * then lands the person on a page that is not Keydra. The session cookie stays where it belongs, so
 * this is not a stolen session — it is a working sign-in that ends somewhere else, which is enough
 * to ask somebody for a password a second time.
 *
 * <p>So the derivation is a development convenience and says so. Elsewhere an unconfigured public
 * URL is a misconfiguration and is reported as one, at the point where something needs it rather
 * than at startup — a Keydra with no identity provider never needs it and should not refuse to
 * start for the want of it.
 */
@ApplicationScoped
public class PublicUrl {

    private final Optional<String> configured;
    private final boolean deriveFromRequest;

    @Inject
    PublicUrl(
            @ConfigProperty(name = "keydra.public-url") Optional<String> configured,
            @ConfigProperty(name = "keydra.public-url-from-request") boolean deriveFromRequest) {
        this.configured = configured;
        this.deriveFromRequest = deriveFromRequest;
    }

    /**
     * Whether there is an address to build a link from at all.
     *
     * <p>Asked before a letter is written rather than after: composing one and then finding there
     * is nowhere for its button to point would be work done to be thrown away, and the caller has a
     * perfectly good answer for this case already.
     */
    public boolean isConfigured() {
        return configured.isPresent() && !configured.get().isBlank();
    }

    /**
     * An absolute address for a path, for the messages that are not answering a request.
     *
     * <p>Empty when nobody has configured one, and that is not a shortcoming to work around: mail
     * goes to a person who is not here, so a link built from whatever address this process happens
     * to be listening on would as often as not be an address they cannot reach. Better to have no
     * link than a wrong one, and the caller says so.
     */
    public Optional<String> absolute(String path) {
        return configured
                .filter(url -> !url.isBlank())
                .map(url -> url.endsWith("/") ? url.substring(0, url.length() - 1) : url)
                .map(url -> url + (path.startsWith("/") ? path : "/" + path));
    }

    public String of(HttpServerRequest request) {
        return configured
                .filter(url -> !url.isBlank())
                .orElseGet(
                        () ->
                                derived(
                                        () -> {
                                            // The same derivation as from a UriInfo, taken from
                                            // the Vert.x request instead, because the second API
                                            // surface has no JAX-RS anything. The authority
                                            // carries the port, which is the part that matters.
                                            String scheme = request.isSSL() ? "https" : "http";
                                            String authority =
                                                    request.authority() == null
                                                            ? request.localAddress().toString()
                                                            : request.authority().toString();
                                            return scheme + "://" + authority;
                                        }));
    }

    public String of(UriInfo uriInfo) {
        return configured
                .filter(url -> !url.isBlank())
                .orElseGet(
                        () ->
                                derived(
                                        () -> {
                                            URI base = uriInfo.getBaseUri();
                                            return base.getScheme() + "://" + base.getAuthority();
                                        }));
    }

    /** The derived address, or a misconfiguration where deriving one is not allowed. */
    private String derived(Supplier<String> fromRequest) {
        if (!deriveFromRequest) {
            throw new PublicUrlNotConfiguredException();
        }
        return fromRequest.get();
    }
}
