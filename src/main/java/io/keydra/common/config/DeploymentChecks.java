package io.keydra.common.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What this deployment has been told that does not match what it is doing.
 *
 * <p>Four questions, and each is answered from two things the deployment itself says rather than
 * from a guess about what it meant. Nothing here is inferred from a name or a shape, and nothing
 * here reports a setting merely for being unset — most of the fifty-odd this application reads have
 * defaults that are right, and a page listing them would be configuration documentation with a
 * worse layout.
 *
 * <p>Deliberately not a health check. A readiness probe that failed for an unset public URL would
 * take an instance out of service for something that is working, and one that only warned would be
 * a signal no orchestrator can act on. These are for a person to fix.
 */
@ApplicationScoped
public class DeploymentChecks {

    private final ProxyObserved proxies;
    private final boolean forwarding;
    private final Optional<String> trustedProxies;
    private final String publicUrl;
    private final boolean cookieSecure;
    private final Optional<String> oidcUrl;

    @Inject
    DeploymentChecks(
            ProxyObserved proxies,
            @ConfigProperty(name = "quarkus.http.proxy.proxy-address-forwarding")
                    boolean forwarding,
            @ConfigProperty(name = "quarkus.http.proxy.trusted-proxies")
                    Optional<String> trustedProxies,
            // Optional rather than String: an unset one is written as the empty value, and the
            // converter reads that as absent rather than as "".
            @ConfigProperty(name = "keydra.public-url") Optional<String> publicUrl,
            @ConfigProperty(name = "keydra.security.cookie-secure") boolean cookieSecure,
            @ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> oidcUrl) {
        this.proxies = proxies;
        this.forwarding = forwarding;
        this.trustedProxies = trustedProxies;
        this.publicUrl = publicUrl.map(String::trim).orElse("");
        this.cookieSecure = cookieSecure;
        // Blank rather than absent is what an unset variable looks like here: the property is
        // written as ${KEYDRA_OIDC_URL:} so that a deployment without one still has the key.
        this.oidcUrl = oidcUrl.filter(url -> !url.isBlank());
    }

    /** Everything worth saying, or nothing at all — which is the ordinary answer. */
    public List<DeploymentNote> notes() {
        List<DeploymentNote> notes = new ArrayList<>();

        if (proxies.behindOne() && !forwarding) {
            notes.add(
                    new DeploymentNote(
                            "KEYDRA_BEHIND_PROXY",
                            "A request arrived through a proxy, and this instance is set not to"
                                    + " believe what a proxy says about where a request came from.",
                            "Every sign-in looks like it came from the proxy, so the checks that"
                                + " compare a sign-in with the ones before it are comparing"
                                + " everybody with everybody, and the limit on attempts counts a"
                                + " whole office as one network."));
        }

        if (forwarding && trustedProxies.map(String::isBlank).orElse(true)) {
            notes.add(
                    new DeploymentNote(
                            "KEYDRA_TRUSTED_PROXIES",
                            "This instance believes what a proxy says about where a request came"
                                    + " from, and no proxies are named.",
                            "Anybody who can reach it can claim any address, which is worse than"
                                + " not asking: a sign-in from anywhere can be made to look like"
                                + " one from the office."));
        }

        if (publicUrl.isEmpty() && oidcUrl.isPresent()) {
            notes.add(
                    new DeploymentNote(
                            "KEYDRA_PUBLIC_URL",
                            "An identity provider is configured and this instance has not been told"
                                    + " the address a browser reaches it at.",
                            "A redirect URI is agreed with the provider in advance and has to match"
                                + " to the character; without this one is guessed from the request,"
                                + " which behind a proxy is the proxy's idea of the host. The same"
                                + " address is what an invitation link is built from."));
        }

        if (!cookieSecure && publicUrl.startsWith("https://")) {
            notes.add(
                    new DeploymentNote(
                            "KEYDRA_COOKIE_SECURE",
                            "This instance is reached over https and its session cookies are not"
                                    + " marked secure.",
                            "A cookie without it travels on a plain request too, so anything that"
                                    + " can make a browser send one has the session."));
        }

        return List.copyOf(notes);
    }
}
