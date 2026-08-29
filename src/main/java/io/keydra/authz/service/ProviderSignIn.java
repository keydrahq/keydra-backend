package io.keydra.authz.service;

import io.keydra.authz.entity.IdentityProviderConfig;
import io.keydra.authz.exception.SignInFailedException;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * The authorization-code flow, run against whichever provider a row describes.
 *
 * <p>One implementation for every provider, because there is only one flow: send somebody to an
 * authorization endpoint, get a code back, swap the code for a token at a token endpoint, and ask
 * who they are. What differs between Keycloak and GitHub is which URLs those are and what the
 * answer's fields are called, and both of those are configuration.
 *
 * <p>PKCE on every flow, including the confidential ones that do not strictly need it. A code
 * intercepted between the provider and Keydra is worthless without the verifier, and the cost of
 * always sending one is a hash.
 */
@ApplicationScoped
public class ProviderSignIn {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 32 bytes, which is what the PKCE specification asks for at the top of its range. */
    private static final int SECRET_BYTES = 32;

    private final WebClient client;
    private final SecureRandom random = new SecureRandom();

    @Inject
    ProviderSignIn(Vertx vertx) {
        this.client = WebClient.create(vertx);
    }

    @PreDestroy
    void close() {
        client.close();
    }

    /** One random value nobody else can guess, base64url with no padding, as PKCE requires. */
    public String secret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Where to send somebody to prove who they are.
     *
     * @param state a value that comes back untouched, so the callback can tell a flow this
     *     application started from one somebody else did
     * @param verifier the PKCE secret; only its hash is sent here
     */
    public String authorizationUrl(
            IdentityProviderConfig provider, String redirectUri, String state, String verifier) {
        if (provider.authorizationEndpoint == null) {
            throw new SignInFailedException(
                    provider.displayName + " has no authorization endpoint configured");
        }
        StringBuilder url = new StringBuilder(provider.authorizationEndpoint);
        url.append(provider.authorizationEndpoint.contains("?") ? '&' : '?');
        append(url, "response_type", "code");
        append(url, "client_id", provider.clientId);
        append(url, "redirect_uri", redirectUri);
        append(url, "scope", provider.scopes);
        append(url, "state", state);
        append(url, "code_challenge", challenge(verifier));
        append(url, "code_challenge_method", "S256");
        url.setLength(url.length() - 1);
        return url.toString();
    }

    /** Who the provider says this is, once the code has been swapped for a token. */
    public record Claims(
            String subject, String username, String email, String name, List<String> groups) {}

    public Uni<Claims> identify(
            IdentityProviderConfig provider, String code, String redirectUri, String verifier) {
        return exchange(provider, code, redirectUri, verifier)
                .flatMap(tokens -> claims(provider, tokens));
    }

    /** The back-channel call: a code and a secret in, a token out. */
    private Uni<JsonObject> exchange(
            IdentityProviderConfig provider, String code, String redirectUri, String verifier) {
        if (provider.tokenEndpoint == null) {
            throw new SignInFailedException(
                    provider.displayName + " has no token endpoint configured");
        }
        MultiMap form = MultiMap.caseInsensitiveMultiMap();
        form.set("grant_type", "authorization_code");
        form.set("code", code);
        form.set("redirect_uri", redirectUri);
        form.set("client_id", provider.clientId);
        form.set("code_verifier", verifier);
        if (provider.clientSecret != null && !provider.clientSecret.isBlank()) {
            form.set("client_secret", provider.clientSecret);
        }

        return client.postAbs(provider.tokenEndpoint)
                // GitHub answers form-encoded unless asked otherwise, and the specification
                // says JSON, so every provider is asked the same way.
                .putHeader("Accept", "application/json")
                .sendForm(io.vertx.mutiny.core.MultiMap.newInstance(form))
                .ifNoItem()
                .after(TIMEOUT)
                .failWith(
                        () ->
                                new SignInFailedException(
                                        provider.displayName + " did not answer in time"))
                .map(
                        response -> {
                            if (response.statusCode() != 200) {
                                // Deliberately not the body: a token endpoint that is unhappy
                                // often echoes the request back, and the request had the
                                // client secret in it.
                                throw new SignInFailedException(
                                        provider.displayName
                                                + " refused the sign-in ("
                                                + response.statusCode()
                                                + ")");
                            }
                            return json(response.bodyAsString(), provider);
                        });
    }

    /**
     * The claims, from the user endpoint where there is one and the id token otherwise.
     *
     * <p>The user endpoint is preferred even for OIDC, so that one claim mapping serves both kinds
     * of provider rather than one each.
     */
    private Uni<Claims> claims(IdentityProviderConfig provider, JsonObject tokens) {
        if (provider.userInfoEndpoint != null && !provider.userInfoEndpoint.isBlank()) {
            String accessToken = tokens.getString("access_token");
            if (accessToken == null) {
                throw new SignInFailedException(
                        provider.displayName + " returned no access token to ask with");
            }
            return userInfo(provider, accessToken).map(document -> read(provider, document));
        }
        return Uni.createFrom().item(read(provider, idTokenClaims(provider, tokens)));
    }

    private Uni<JsonObject> userInfo(IdentityProviderConfig provider, String accessToken) {
        return client.getAbs(provider.userInfoEndpoint)
                .putHeader("Accept", "application/json")
                .putHeader("Authorization", "Bearer " + accessToken)
                .send()
                .ifNoItem()
                .after(TIMEOUT)
                .failWith(
                        () ->
                                new SignInFailedException(
                                        provider.displayName + " did not answer in time"))
                .map(
                        response -> {
                            if (response.statusCode() != 200) {
                                throw new SignInFailedException(
                                        provider.displayName
                                                + " would not say who this is ("
                                                + response.statusCode()
                                                + ")");
                            }
                            return json(response.bodyAsString(), provider);
                        });
    }

    /**
     * The id token's payload, read without checking its signature.
     *
     * <p>Deliberate, and the specification allows it: this token arrived over TLS in the answer to
     * a request Keydra made directly to the provider's own token endpoint, so the connection is
     * what establishes where it came from — there is no untrusted party in between for a signature
     * to protect against. OIDC Core says as much in §3.1.3.7. A token that arrived any other way
     * would need its signature checked, and none does.
     */
    private static JsonObject idTokenClaims(IdentityProviderConfig provider, JsonObject tokens) {
        String idToken = tokens.getString("id_token");
        if (idToken == null) {
            throw new SignInFailedException(
                    provider.displayName
                            + " has no user endpoint configured and returned no id token");
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new SignInFailedException(
                    provider.displayName + " returned a malformed id token");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            return new JsonObject(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException unreadable) {
            throw new SignInFailedException(
                    provider.displayName + " returned a malformed id token");
        }
    }

    private static Claims read(IdentityProviderConfig provider, JsonObject document) {
        String subject = string(document, provider.subjectClaim);
        if (subject == null || subject.isBlank()) {
            throw new SignInFailedException(
                    provider.displayName
                            + " sent no "
                            + provider.subjectClaim
                            + ", which is what identifies the person");
        }
        String username = string(document, provider.usernameClaim);
        return new Claims(
                subject,
                // A provider that sends no username still sends a subject, and a person has to
                // be called something. Better a name nobody chose than a refused sign-in.
                username == null || username.isBlank() ? subject : username,
                string(document, provider.emailClaim),
                string(document, provider.nameClaim),
                groups(document, provider.groupsClaim));
    }

    /** A claim's value as text, tolerating the numeric ids some providers use. */
    private static String string(JsonObject document, String claim) {
        if (claim == null || claim.isBlank()) {
            return null;
        }
        Object value = document.getValue(claim);
        return value == null ? null : String.valueOf(value);
    }

    /** Group claims arrive as an array from most providers and as one string from some. */
    private static List<String> groups(JsonObject document, String claim) {
        if (claim == null || claim.isBlank()) {
            return List.of();
        }
        Object value = document.getValue(claim);
        if (value instanceof JsonArray array) {
            return array.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        if (value instanceof String single && !single.isBlank()) {
            return List.of(single);
        }
        return List.of();
    }

    private static JsonObject json(String body, IdentityProviderConfig provider) {
        try {
            return new JsonObject(body);
        } catch (RuntimeException notJson) {
            throw new SignInFailedException(
                    provider.displayName + " answered with something that is not JSON");
        }
    }

    private static String challenge(String verifier) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private static void append(StringBuilder url, String name, String value) {
        url.append(name)
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                .append('&');
    }
}
