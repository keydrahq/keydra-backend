package io.keydra.tunnels;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.InProcessSshResource;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A target reached through an SSH tunnel.
 *
 * <p>That the traffic went through the tunnel is not inferred, it is asked: the test's SSH server
 * records every forward it is told to open, and the assertions read that record. A tunnel that was
 * bypassed would leave it empty.
 *
 * <p>The tunnel is a row of its own now, so these go through the API that describes one and then
 * point a target at it — which is also the shape of the thing being tested: one jump host, many
 * things reaching through it.
 */
@QuarkusTest
@WithTestResource(InProcessSshResource.class)
@WithTestResource(RedisTargetsResource.class)
class SshTunnelTest {

    @BeforeEach
    void setUp() {
        // Profiles first: they are what points at a tunnel, and a tunnel with something
        // pointing at it refuses to be removed — which is itself one of the tests below.
        ConnectionFixtures.deleteAllProfiles();
        TunnelFixtures.deleteAll();
        InProcessSshResource.resetForwards();
        RedisTargetsResource.flushRedis();
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    private static Map<String, Object> profile(String name, Integer tunnelId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("host", config(RedisTargetsResource.REDIS_HOST));
        body.put("port", Integer.valueOf(config(RedisTargetsResource.REDIS_PORT)));
        body.put("tls", false);
        body.put("database", 0);
        body.put("type", "STANDALONE");
        if (tunnelId != null) {
            body.put("tunnelId", tunnelId);
        }
        return body;
    }

    /** A tunnel row pointing at the test's SSH server. */
    private static Map<String, Object> tunnel(String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("host", config(InProcessSshResource.SSH_HOST));
        body.put("port", Integer.valueOf(config(InProcessSshResource.SSH_PORT)));
        body.put("username", config(InProcessSshResource.SSH_USER));
        body.put("password", config(InProcessSshResource.SSH_PASSWORD));
        return body;
    }

    private static int createTunnel(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/tunnels")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private static int create(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/connections")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void reachesTheTargetThroughTheTunnel() {
        int id = create(profile("through-the-tunnel", createTunnel(tunnel("jump host"))));

        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .statusCode(200)
                .body("state", equalTo("UP"))
                .body("server.flavor", equalTo("redis"));

        // The SSH server was asked to reach the target, which is what "through" means.
        assertThat(InProcessSshResource.forwardCount(), greaterThan(0));
        assertThat(
                InProcessSshResource.lastDestination().getPort(),
                equalTo(Integer.valueOf(config(RedisTargetsResource.REDIS_PORT))));
    }

    @Test
    void doesNotUseTheTunnelWhenTheProfileDoesNotAskForOne() {
        // The tunnel exists and this target does not point at it.
        createTunnel(tunnel("jump host"));
        int id = create(profile("direct", null));

        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .statusCode(200)
                .body("state", equalTo("UP"));

        // Nothing was forwarded: a profile that wants no tunnel pays nothing for one existing.
        assertThat(InProcessSshResource.forwardCount(), equalTo(0));
    }

    @Test
    void browsesAndEditsThroughTheTunnel() {
        int id = create(profile("browsing", createTunnel(tunnel("jump host"))));

        given().contentType(ContentType.JSON)
                .body(Map.of("operation", "setString", "key", "tunnelled", "value", "hello"))
                .when()
                .post("/api/v1/connections/{id}/value", id)
                .then()
                .statusCode(200);

        given().when()
                .get("/api/v1/connections/{id}/value?key=tunnelled", id)
                .then()
                .statusCode(200)
                .body("value.text", equalTo("hello"));

        assertThat(InProcessSshResource.forwardCount(), greaterThan(0));
    }

    @Test
    void refusesATunnelWithNoCredential() {
        Map<String, Object> body = tunnel("no-credential");
        body.remove("password");

        // Refused where somebody can see it rather than at the first connection: a jump host
        // with neither a key nor a password is one nothing can log in to, and finding that
        // out as a target that will not come up says the wrong thing about the target.
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/tunnels")
                .then()
                .statusCode(409);
    }

    @Test
    void refusesATunnelWithTheWrongPassword() {
        Map<String, Object> wrong = tunnel("wrong-password");
        wrong.put("password", "not-the-password");
        int id = create(profile("behind-a-closed-door", createTunnel(wrong)));

        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .statusCode(200)
                .body("state", equalTo("DOWN"));
    }

    @Test
    void saysWhetherATunnelWorksAndWhatKeyItPresented() {
        int id = createTunnel(tunnel("checked"));

        given().when()
                .post("/api/v1/tunnels/{id}/check", id)
                .then()
                .statusCode(200)
                .body("reachable", equalTo(true))
                // Offered so pinning it is a copy and a save rather than a trip to a terminal.
                .body("fingerprint", org.hamcrest.Matchers.startsWith("SHA256:"));
    }

    @Test
    void triesAJumpHostThatHasNotBeenSavedYet() {
        // The whole point of the button beside the form: find out the credential is wrong
        // while somebody is looking at it, rather than the next time something behind the
        // jump host is needed.
        given().contentType(ContentType.JSON)
                .body(tunnel("never-saved"))
                .when()
                .post("/api/v1/tunnels/check")
                .then()
                .statusCode(200)
                .body("reachable", equalTo(true))
                .body("fingerprint", org.hamcrest.Matchers.startsWith("SHA256:"));

        // And nothing was written: a test is not a save.
        given().when().get("/api/v1/tunnels").then().statusCode(200).body("", hasSize(0));
    }

    @Test
    void triesAnEditWithTheSecretItWasNotSentAgain() {
        int id = createTunnel(tunnel("edited"));

        // An edit form never carries the stored password back, so a test of one must use what
        // is stored — otherwise every check of an unchanged credential would fail.
        Map<String, Object> renamed = new HashMap<>();
        renamed.put("name", "edited");
        renamed.put("host", config(InProcessSshResource.SSH_HOST));
        renamed.put("port", Integer.valueOf(config(InProcessSshResource.SSH_PORT)));
        renamed.put("username", config(InProcessSshResource.SSH_USER));

        given().contentType(ContentType.JSON)
                .body(renamed)
                .when()
                .post("/api/v1/tunnels/check?id=" + id)
                .then()
                .statusCode(200)
                .body("reachable", equalTo(true));
    }

    @Test
    void refusesAKeyThatIsNotTheOneItWasToldToExpect() {
        Map<String, Object> pinned = tunnel("pinned-wrong");
        pinned.put("hostKeyFingerprint", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        int id = createTunnel(pinned);

        // Everything Keydra holds for everything behind a jump host travels through it, so
        // something else answering on its address must not be quietly accepted.
        given().when()
                .post("/api/v1/tunnels/{id}/check", id)
                .then()
                .statusCode(200)
                .body("reachable", equalTo(false))
                .body("message", org.hamcrest.Matchers.containsString("host key changed"));
    }

    @Test
    void refusesToRemoveATunnelSomethingStillReachesThrough() {
        int tunnelId = createTunnel(tunnel("in-use"));
        create(profile("depends-on-it", tunnelId));

        // The alternative is targets quietly trying to connect directly to an address that is
        // not reachable, which looks like a server being down.
        given().when()
                .delete("/api/v1/tunnels/{id}", tunnelId)
                .then()
                .statusCode(409)
                .body("message", org.hamcrest.Matchers.containsString("reached through"));
    }

    @Test
    void oneTunnelServesEveryTargetBehindIt() {
        int tunnelId = createTunnel(tunnel("shared"));
        int first = create(profile("first", tunnelId));
        int second = create(profile("second", tunnelId));

        given().when().post("/api/v1/connections/{id}/test", first).then().statusCode(200);
        given().when().post("/api/v1/connections/{id}/test", second).then().statusCode(200);

        // The point of the tunnel being a row: two targets behind one jump host are one SSH
        // session with two forwards, not two sessions and two copies of one credential.
        assertThat(InProcessSshResource.sessionCount(), equalTo(1));
        assertThat(InProcessSshResource.forwardCount(), greaterThan(1));
    }

    @Test
    void neverReturnsTheTunnelCredentials() {
        createTunnel(tunnel("secrets"));

        String body =
                given().when()
                        .get("/api/v1/tunnels")
                        .then()
                        .statusCode(200)
                        .body("[0].hasPassword", is(true))
                        .extract()
                        .asString();

        // The same rule as the target's own password: stored, reported as present, never sent.
        assertThat(body.contains(config(InProcessSshResource.SSH_PASSWORD)), is(false));
    }

    @Test
    void opensAFreshSessionAfterTheTunnelIsEdited() {
        int tunnelId = createTunnel(tunnel("edited"));
        int id = create(profile("behind-it", tunnelId));
        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .body("state", equalTo("UP"));

        Map<String, Object> edited = tunnel("edited-again");
        // Absent secrets mean "keep what is stored", so the password is not resent.
        edited.remove("password");
        given().contentType(ContentType.JSON)
                .body(edited)
                .when()
                .put("/api/v1/tunnels/{id}", tunnelId)
                .then()
                .statusCode(200);

        InProcessSshResource.resetForwards();

        // An edit drops the session, because the jump host may now be a different one. The next
        // use has to open a new one rather than fail.
        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .statusCode(200)
                .body("state", equalTo("UP"));
        assertThat(InProcessSshResource.forwardCount(), greaterThan(0));
    }
}
