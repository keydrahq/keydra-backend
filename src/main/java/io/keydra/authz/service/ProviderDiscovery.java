package io.keydra.authz.service;

import io.keydra.authz.exception.SignInFailedException;
import io.keydra.common.net.BlockedAddressException;
import io.keydra.common.net.EgressGuard;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;

/**
 * Asks an OIDC provider where its endpoints are.
 *
 * <p>Run when a provider is saved rather than when somebody signs in. Discovery is a network call
 * to somebody else's server, and putting one in front of every sign-in means every sign-in fails
 * whenever they are slow. Saving the answer also means an administrator can see what Keydra thinks
 * the endpoints are, which is the first question when a sign-in goes wrong.
 */
@ApplicationScoped
public class ProviderDiscovery {

    /** Where every OIDC provider publishes its document, by the specification. */
    private static final String WELL_KNOWN = "/.well-known/openid-configuration";

    /**
     * Long enough for a provider on the other side of the world, short enough that saving a
     * provider with a wrong issuer answers rather than hangs.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final EgressGuard egress;

    @Inject
    ProviderDiscovery(Vertx vertx, EgressGuard egress) {
        this.client = WebClient.create(vertx);
        this.egress = egress;
    }

    @PreDestroy
    void close() {
        client.close();
    }

    /** What a discovery document says, or a failure naming what could not be found. */
    public record Endpoints(String authorization, String token, String userInfo) {}

    /**
     * Fetches an issuer's discovery document.
     *
     * <p>The address is checked before it is fetched. Configuring a provider is an administrator's
     * to do, so this is less of an opening than a webhook is — but it is the same opening, and the
     * answer to "which of these forms should be allowed to make the server fetch a link-local
     * address" is none of them.
     */
    public Uni<Endpoints> discover(String issuer) {
        String url = issuer.replaceAll("/+$", "") + WELL_KNOWN;

        return egress.check(url)
                .onFailure(BlockedAddressException.class)
                .transform(blocked -> new SignInFailedException(blocked.getMessage()))
                .flatMap(ignored -> fetch(url, issuer));
    }

    private Uni<Endpoints> fetch(String url, String issuer) {
        return client.getAbs(url)
                .putHeader("Accept", "application/json")
                .send()
                .ifNoItem()
                .after(TIMEOUT)
                .failWith(() -> new SignInFailedException(issuer + " did not answer in time"))
                .map(
                        response -> {
                            if (response.statusCode() != 200) {
                                throw new SignInFailedException(
                                        "No discovery document at "
                                                + url
                                                + " — it answered "
                                                + response.statusCode());
                            }
                            JsonObject document = asJson(response.bodyAsString(), url);
                            return new Endpoints(
                                    required(document, "authorization_endpoint", url),
                                    required(document, "token_endpoint", url),
                                    // Optional in the specification, and some providers do
                                    // without it. The claims then come from the id token.
                                    document.getString("userinfo_endpoint"));
                        });
    }

    private static JsonObject asJson(String body, String url) {
        try {
            return new JsonObject(body);
        } catch (RuntimeException notJson) {
            throw new SignInFailedException(url + " answered with something that is not JSON");
        }
    }

    private static String required(JsonObject document, String field, String url) {
        String value = document.getString(field);
        if (value == null || value.isBlank()) {
            throw new SignInFailedException(
                    "The discovery document at " + url + " names no " + field);
        }
        return value;
    }
}
