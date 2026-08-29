package io.keydra.connections.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the probe endpoint end to end against real servers.
 *
 * <p>Covers the seam the registry tests cannot: the REST layer returns a {@code Uni}, so the
 * transactional profile lookup has to leave the IO thread. Calling the registry directly would
 * never catch that.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class ConnectionsProbeTest {

    @BeforeEach
    void clean() {
        ConnectionFixtures.deleteAllProfiles();
    }

    private static int create(String name, String hostKey, String portKey) {
        return given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                name,
                                "host",
                                ConfigProvider.getConfig().getValue(hostKey, String.class),
                                "port",
                                ConfigProvider.getConfig().getValue(portKey, Integer.class),
                                "tls",
                                false,
                                "database",
                                0,
                                "type",
                                "STANDALONE"))
                .when()
                .post("/api/v1/connections")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void probesARedisTargetThroughTheApi() {
        int id =
                create(
                        "redis-target",
                        RedisTargetsResource.REDIS_HOST,
                        RedisTargetsResource.REDIS_PORT);

        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .statusCode(200)
                .body("state", equalTo("UP"))
                .body("server.flavor", equalTo("redis"))
                .body("server.version", startsWith("8."))
                .body("checkedAt", notNullValue());
    }

    @Test
    void probesAValkeyTargetThroughTheApi() {
        int id =
                create(
                        "valkey-target",
                        RedisTargetsResource.VALKEY_HOST,
                        RedisTargetsResource.VALKEY_PORT);

        given().when()
                .post("/api/v1/connections/{id}/test", id)
                .then()
                .statusCode(200)
                .body("state", equalTo("UP"))
                .body("server.flavor", equalTo("valkey"))
                .body("server.version", startsWith("9."));
    }

    @Test
    void recordsTheProbeResultOnTheProfile() {
        int id =
                create(
                        "recorded",
                        RedisTargetsResource.REDIS_HOST,
                        RedisTargetsResource.REDIS_PORT);

        given().when().post("/api/v1/connections/{id}/test", id).then().statusCode(200);

        // the list endpoint must now report the detected server, not UNKNOWN
        given().when()
                .get("/api/v1/connections/{id}", id)
                .then()
                .statusCode(200)
                .body("status.state", equalTo("UP"))
                .body("status.server.flavor", equalTo("redis"));
    }

    @Test
    void probesANewProfileWithoutBeingAsked() {
        // A row must not sit at "unknown" until someone clicks test: creating a
        // profile starts a probe, and the status arrives on its own.
        int id =
                create(
                        "auto-probed",
                        RedisTargetsResource.REDIS_HOST,
                        RedisTargetsResource.REDIS_PORT);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/connections/{id}", id)
                                        .then()
                                        .statusCode(200)
                                        .body("status.state", equalTo("UP"))
                                        .body("status.server.flavor", equalTo("redis")));
    }

    @Test
    void reprobesAfterTheTargetIsEdited() {
        int id = create("edited", RedisTargetsResource.REDIS_HOST, RedisTargetsResource.REDIS_PORT);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/connections/{id}", id)
                                        .then()
                                        .body("status.state", equalTo("UP")));

        // Point it somewhere dead; the cached client must be dropped and re-probed.
        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                "edited",
                                "host",
                                "127.0.0.1",
                                "port",
                                1,
                                "tls",
                                false,
                                "database",
                                0,
                                "type",
                                "STANDALONE"))
                .when()
                .put("/api/v1/connections/{id}", id)
                .then()
                .statusCode(200);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/connections/{id}", id)
                                        .then()
                                        .body("status.state", equalTo("DOWN")));
    }

    @Test
    void returns404WhenProbingAnUnknownProfile() {
        given().when().post("/api/v1/connections/{id}/test", 999999).then().statusCode(404);
    }
}
