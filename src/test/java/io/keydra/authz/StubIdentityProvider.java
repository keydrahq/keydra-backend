package io.keydra.authz;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An identity provider, small enough to be a fixture.
 *
 * <p>Standing one of these up in a container would test Keycloak. What needs testing is Keydra's
 * half of the flow — that it sends the right parameters, refuses a callback whose state does not
 * match, checks PKCE end to end, and turns whatever comes back into an account — and every one of
 * those is exercised better by a provider whose answers a test can decide.
 *
 * <p>It is a real provider in the ways that matter: it publishes a discovery document, it will not
 * hand out a token for a verifier that does not hash to the challenge it was given, and it answers
 * JSON of the shape the specification describes.
 */
@Path("/stub-idp")
@PermitAll
public class StubIdentityProvider {

    /** What each issued code was granted for, so the token endpoint can check it. */
    private static final Map<String, Grant> ISSUED = new ConcurrentHashMap<>();

    /** Who the next sign-in will be, which a test sets before starting one. */
    private static volatile String subject = "stub-subject";

    private static volatile String username = "stub-user";
    private static volatile String[] groups = {};

    record Grant(String challenge, String subject, String username, String[] groups) {}

    public static void willIdentify(String newSubject, String newUsername, String... newGroups) {
        subject = newSubject;
        username = newUsername;
        groups = newGroups;
    }

    public static void reset() {
        ISSUED.clear();
        willIdentify("stub-subject", "stub-user");
    }

    @GET
    @Path("/.well-known/openid-configuration")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> discovery(@Context UriInfo uriInfo) {
        String base = uriInfo.getBaseUri().toString().replaceAll("/+$", "") + "/stub-idp";
        return Map.of(
                "issuer", base,
                "authorization_endpoint", base + "/authorize",
                "token_endpoint", base + "/token",
                "userinfo_endpoint", base + "/userinfo");
    }

    /** Consents immediately and sends the browser back, as a provider would after a password. */
    @GET
    @Path("/authorize")
    public Response authorize(
            @QueryParam("redirect_uri") String redirectUri,
            @QueryParam("state") String state,
            @QueryParam("code_challenge") String challenge) {

        String code = "code-" + ISSUED.size() + "-" + System.nanoTime();
        ISSUED.put(code, new Grant(challenge, subject, username, groups));

        return Response.seeOther(URI.create(redirectUri + "?code=" + code + "&state=" + state))
                .build();
    }

    @POST
    @Path("/token")
    @Produces(MediaType.APPLICATION_JSON)
    public Response token(
            @FormParam("code") String code, @FormParam("code_verifier") String verifier) {

        Grant grant = ISSUED.remove(code);
        if (grant == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid_grant"))
                    .build();
        }
        // The point of PKCE, and therefore the point of testing it: a code alone is not enough.
        if (!challengeOf(verifier).equals(grant.challenge())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid_grant"))
                    .build();
        }
        return Response.ok(
                        Map.of(
                                "access_token",
                                "token-for-" + grant.subject(),
                                "token_type",
                                "Bearer",
                                "expires_in",
                                300))
                .build();
    }

    @GET
    @Path("/userinfo")
    @Produces(MediaType.APPLICATION_JSON)
    public Response userInfo(@HeaderParam("Authorization") String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer token-for-")) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(
                        Map.of(
                                "sub",
                                subject,
                                "preferred_username",
                                username,
                                "email",
                                username + "@example.com",
                                "name",
                                "Stub " + username,
                                "groups",
                                java.util.List.of(groups)))
                .build();
    }

    private static String challengeOf(String verifier) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
