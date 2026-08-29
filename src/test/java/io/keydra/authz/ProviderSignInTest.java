package io.keydra.authz;

import static io.keydra.authz.LocalLoginTest.setUpAdministrator;
import static io.keydra.authz.LocalLoginTest.signIn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Signing in through a provider that was configured while Keydra was running.
 *
 * <p>End to end and deliberately so. Every piece of this can be right on its own while the whole is
 * broken — a redirect URI off by a slash, a state cookie the browser will not send back, a PKCE
 * verifier that never leaves the first request — and none of those show up anywhere but in a flow
 * that actually completes.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
// The same resources as the other classes that enforce security, so the application is not
// restarted between them — see the note on LocalLoginTest.
@WithTestResource(RedisTargetsResource.class)
class ProviderSignInTest {

    private String adminSession;
    private String base;

    @BeforeEach
    void configureAProvider() {
        AuthzFixtures.deleteEverythingButRoles();
        StubIdentityProvider.reset();

        base =
                "http://localhost:"
                        + ConfigProvider.getConfig()
                                .getValue("quarkus.http.test-port", Integer.class);

        setUpAdministrator("ada");
        adminSession = signIn("ada");

        addProvider(request("stub", "Stub provider", true));
    }

    private Map<String, Object> request(String key, String displayName, boolean autoCreate) {
        Map<String, Object> request = new HashMap<>();
        request.put("key", key);
        request.put("displayName", displayName);
        request.put("kind", "OIDC");
        request.put("issuer", base + "/stub-idp");
        request.put("clientId", "keydra");
        request.put("clientSecret", "a-client-secret");
        request.put("groupsClaim", "groups");
        request.put("autoCreateUsers", autoCreate);
        return request;
    }

    private void addProvider(Map<String, Object> request) {
        given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/v1/authz/providers")
                .then()
                .statusCode(201);
    }

    @Test
    void discoversWhereToSendPeopleWhenTheProviderIsSaved() {
        // At configuration time rather than at every sign-in: this is where somebody is
        // waiting for an answer and able to do something about a wrong issuer.
        given().cookie("keydra_session", adminSession)
                .when()
                .get("/api/v1/authz/providers")
                .then()
                .statusCode(200)
                .body("[0].endpointsDiscovered", is(true))
                .body("[0].authorizationEndpoint", containsString("/stub-idp/authorize"))
                .body("[0].tokenEndpoint", containsString("/stub-idp/token"))
                .body("[0].userInfoEndpoint", containsString("/stub-idp/userinfo"))
                .body("[0].redirectUri", containsString("/api/v1/auth/providers/stub/callback"));
    }

    @Test
    void refusesAnIssuerThatPublishesNothing() {
        Map<String, Object> broken = request("nowhere", "Nowhere", true);
        broken.put("issuer", base + "/stub-idp/not-a-provider");

        given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(broken)
                .when()
                .post("/api/v1/authz/providers")
                .then()
                // Refused at the point of configuration, so nobody meets it as a button that
                // does nothing.
                .statusCode(409);
    }

    @Test
    void offersTheProviderToAnybodyAtTheLoginPage() {
        given().when()
                .get("/api/v1/auth/providers")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].key", equalTo("stub"))
                .body("[0].displayName", equalTo("Stub provider"));
    }

    @Test
    void neverReturnsTheClientSecret() {
        String body =
                given().cookie("keydra_session", adminSession)
                        .when()
                        .get("/api/v1/authz/providers")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();

        assertThat(body, not(containsString("a-client-secret")));
        assertThat(body, containsString("\"hasClientSecret\":true"));
    }

    @Test
    void sendsPeopleToTheProviderWithAStateAndAChallenge() {
        Response started = start();

        String location = started.getHeader("Location");
        assertThat(location, containsString("/stub-idp/authorize"));
        assertThat(location, containsString("code_challenge_method=S256"));
        assertThat(location, containsString("client_id=keydra"));
        // The flow's own secrets, in a cookie only this application can read.
        assertThat(started.getCookie("keydra_signin"), not(nullValue()));
    }

    @Test
    void signsSomebodyInAndGivesThemTheSameSessionAPasswordWouldHave() {
        String session = completeAFlow();

        given().cookie("keydra_session", session)
                .when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(200)
                .body("authenticated", is(true))
                .body("username", equalTo("stub-user"));
    }

    @Test
    void createsTheAccountWithNoPasswordAndNoAccess() {
        completeAFlow();

        given().cookie("keydra_session", adminSession)
                .when()
                .get("/api/v1/authz/users")
                .then()
                .statusCode(200)
                .body("find { it.username == 'stub-user' }.provider", equalTo("stub"))
                // Signed into somewhere else. A password here would be a second way in that
                // the provider does not know about and cannot close.
                .body("find { it.username == 'stub-user' }.hasPassword", is(false));

        // Proving who you are is not being allowed in.
        given().cookie("keydra_session", completeAFlow())
                .when()
                .get("/api/v1/auth/permissions")
                .then()
                .statusCode(200)
                .body("instance", hasSize(0))
                .body("connections", equalTo(Map.of()));
    }

    @Test
    void putsPeopleInTheGroupsTheirProviderNames() {
        Long group = createGroup("platform");
        mapClaim("platform-team", group);
        StubIdentityProvider.willIdentify("s-1", "deniz", "platform-team");

        completeAFlow();

        given().cookie("keydra_session", adminSession)
                .when()
                .get("/api/v1/authz/users")
                .then()
                .statusCode(200)
                .body("find { it.username == 'deniz' }.groups", contains("platform"));
    }

    @Test
    void takesThemOutAgainWhenTheProviderStopsSayingSo() {
        Long group = createGroup("platform");
        mapClaim("platform-team", group);

        StubIdentityProvider.willIdentify("s-1", "deniz", "platform-team");
        completeAFlow();

        // The directory is the source of truth for the groups it maps, which is the whole
        // reason to map one: removing somebody there removes their access here.
        StubIdentityProvider.willIdentify("s-1", "deniz");
        completeAFlow();

        given().cookie("keydra_session", adminSession)
                .when()
                .get("/api/v1/authz/users")
                .then()
                .statusCode(200)
                .body("find { it.username == 'deniz' }.groups", hasSize(0));
    }

    @Test
    void leavesGroupsNobodyMappedAlone() {
        Long mapped = createGroup("from-the-directory");
        Long byHand = createGroup("by-hand");
        mapClaim("platform-team", mapped);

        StubIdentityProvider.willIdentify("s-1", "deniz", "platform-team");
        completeAFlow();

        Long deniz = userId("deniz");
        given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(Map.of("userId", deniz))
                .when()
                .post("/api/v1/authz/groups/" + byHand + "/members")
                .then()
                .statusCode(204);

        completeAFlow();

        // An administrator put them there for a reason the directory has no opinion about.
        given().cookie("keydra_session", adminSession)
                .when()
                .get("/api/v1/authz/users")
                .then()
                .statusCode(200)
                .body("find { it.username == 'deniz' }.groups", hasSize(2));
    }

    @Test
    void refusesACallbackWhoseStateDoesNotMatch() {
        Response started = start();
        String code = codeFrom(started);

        Response callback =
                given().urlEncodingEnabled(false)
                        .redirects()
                        .follow(false)
                        .cookie("keydra_signin", started.getCookie("keydra_signin"))
                        .when()
                        .get(
                                "/api/v1/auth/providers/stub/callback?code="
                                        + code
                                        + "&state=a-state-nobody-issued");

        // The one check between this endpoint and signing somebody into an account they do
        // not own.
        assertThat(callback.getHeader("Location"), containsString("signInError"));
        assertThat(callback.getCookie("keydra_session"), is(nullValue()));
    }

    @Test
    void refusesACallbackWithNoFlowCookieAtAll() {
        Response started = start();

        Response callback =
                given().urlEncodingEnabled(false)
                        .redirects()
                        .follow(false)
                        .when()
                        .get(
                                "/api/v1/auth/providers/stub/callback?code="
                                        + codeFrom(started)
                                        + "&state="
                                        + stateFrom(started));

        assertThat(callback.getHeader("Location"), containsString("signInError"));
        assertThat(callback.getCookie("keydra_session"), is(nullValue()));
    }

    @Test
    void refusesSomebodyTheInstanceHasNoAccountFor() {
        given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(request("closed", "Closed provider", false))
                .when()
                .post("/api/v1/authz/providers")
                .then()
                .statusCode(201);

        Response started = start("closed");
        Response callback = callback("closed", started);

        assertThat(callback.getHeader("Location"), containsString("signInError"));
        assertThat(callback.getCookie("keydra_session"), is(nullValue()));
    }

    // --- Driving the flow ---------------------------------------------------

    private Response start() {
        return start("stub");
    }

    private Response start(String key) {
        Response started =
                given().redirects()
                        .follow(false)
                        .when()
                        .get("/api/v1/auth/providers/" + key + "/start");
        assertThat(started.statusCode(), is(303));
        return started;
    }

    /**
     * What the provider would send back, fetched the way a browser would.
     *
     * <p>Encoding off, throughout. A browser sends a redirect's Location exactly as it was given;
     * REST Assured helpfully encodes what it is handed, which turns the {@code %3A} of an already
     * encoded redirect URI into {@code %253A} and leaves the provider redirecting somewhere that
     * does not exist. The test would then be measuring REST Assured rather than Keydra.
     */
    private Response consent(Response started) {
        return follow(started.getHeader("Location"));
    }

    private Response follow(String url) {
        return given().urlEncodingEnabled(false)
                .redirects()
                .follow(false)
                .when()
                .get(url.substring(base.length()));
    }

    private String codeFrom(Response started) {
        return parameter(consent(started).getHeader("Location"), "code");
    }

    private String stateFrom(Response started) {
        return parameter(consent(started).getHeader("Location"), "state");
    }

    private Response callback(String key, Response started) {
        String back = consent(started).getHeader("Location");
        return given().urlEncodingEnabled(false)
                .redirects()
                .follow(false)
                .cookie("keydra_signin", started.getCookie("keydra_signin"))
                .when()
                .get(back.substring(base.length()));
    }

    /** The whole flow, ending in a session cookie. */
    private String completeAFlow() {
        Response started = start();
        Response callback = callback("stub", started);

        assertThat(callback.getHeader("Location"), not(containsString("signInError")));
        String session = callback.getCookie("keydra_session");
        assertThat(session, not(nullValue()));
        return session;
    }

    private static String parameter(String url, String name) {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts[0].equals(name)) {
                return parts.length > 1 ? parts[1] : "";
            }
        }
        throw new AssertionError("No " + name + " in " + url);
    }

    // --- Fixtures -----------------------------------------------------------

    private Long createGroup(String name) {
        return given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(Map.of("name", name))
                .when()
                .post("/api/v1/authz/groups")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private void mapClaim(String claimValue, Long groupId) {
        Long provider =
                given().cookie("keydra_session", adminSession)
                        .when()
                        .get("/api/v1/authz/providers")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getLong("find { it.key == 'stub' }.id");

        given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(Map.of("claimValue", claimValue, "groupId", groupId))
                .when()
                .post("/api/v1/authz/providers/" + provider + "/group-mappings")
                .then()
                .statusCode(204);
    }

    private Long userId(String username) {
        return given().cookie("keydra_session", adminSession)
                .when()
                .get("/api/v1/authz/users")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("find { it.username == '" + username + "' }.id");
    }
}
