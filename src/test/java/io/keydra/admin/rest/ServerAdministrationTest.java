package io.keydra.admin.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.keydra.AbstractTestBase;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Administering a target: what it is configured to do, and how it keeps its data. */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class ServerAdministrationTest extends AbstractTestBase {

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "admin-target",
                                        "host",
                                        ConfigProvider.getConfig()
                                                .getValue(
                                                        RedisTargetsResource.REDIS_HOST,
                                                        String.class),
                                        "port",
                                        ConfigProvider.getConfig()
                                                .getValue(
                                                        RedisTargetsResource.REDIS_PORT,
                                                        Integer.class),
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
    void reportsWhatTheServerIsConfiguredToDo() {
        given().when()
                .get("/api/v1/connections/{id}/admin/settings?match=maxmemory*", connectionId)
                .then()
                .statusCode(200)
                .body("size()", greaterThan(1))
                .body("name", hasItem("maxmemory-policy"));
    }

    @Test
    void neverReturnsASettingThatIsASecret() {
        // masterauth rather than requirepass, which is the same kind of secret and the same
        // rule — but setting requirepass would lock out the connection doing the asking,
        // which is a fact about Redis rather than about this code.
        RedisTargetsResource.execRedis("CONFIG", "SET", "masterauth", "hunter2");
        try {
            String value =
                    given().when()
                            .get(
                                    "/api/v1/connections/{id}/admin/settings?match=masterauth",
                                    connectionId)
                            .then()
                            .statusCode(200)
                            .extract()
                            .jsonPath()
                            .getString("find { it.name == 'masterauth' }.value");

            // The setting is still listed, because knowing one is set is the useful part.
            org.hamcrest.MatcherAssert.assertThat(value, equalTo("(set)"));
            org.hamcrest.MatcherAssert.assertThat(value, not(containsString("hunter2")));
        } finally {
            RedisTargetsResource.execRedis("CONFIG", "SET", "masterauth", "");
        }
    }

    @Test
    void changesASettingWhileTheServerRuns() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "maxmemory-samples", "value", "7"))
                .when()
                .post("/api/v1/connections/{id}/admin/settings", connectionId)
                .then()
                .statusCode(204);

        org.hamcrest.MatcherAssert.assertThat(
                RedisTargetsResource.execRedis("CONFIG", "GET", "maxmemory-samples"),
                containsString("7"));

        RedisTargetsResource.execRedis("CONFIG", "SET", "maxmemory-samples", "5");
    }

    @Test
    void reportsARefusedValueAsTheCallersProblemRatherThanAFault() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "maxmemory-policy", "value", "nonsense"))
                .when()
                .post("/api/v1/connections/{id}/admin/settings", connectionId)
                .then()
                // A value out of range is a bad request, and the server's own words say
                // which values would have been taken.
                .statusCode(400)
                .body(containsString("maxmemory-policy"));
    }

    @Test
    void saysHowTheServerIsKeepingItsData() {
        given().when()
                .get("/api/v1/connections/{id}/admin/persistence", connectionId)
                .then()
                .statusCode(200)
                .body("lastSaveFailed", equalTo(false))
                .body("$", org.hamcrest.Matchers.hasKey("changesSinceSave"));
    }

    @Test
    void asksForASnapshotWithoutWaitingForIt() {
        // Accepted rather than done: the server writes in the background, which is the
        // whole reason the foreground form is not what this calls.
        given().when()
                .post("/api/v1/connections/{id}/admin/persistence/snapshot", connectionId)
                .then()
                .statusCode(202);
    }
}
