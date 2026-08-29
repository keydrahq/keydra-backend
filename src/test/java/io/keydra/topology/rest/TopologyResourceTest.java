package io.keydra.topology.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.engine.Capabilities;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Capabilities are asked of a real server, so this uses one.
 *
 * <p>The cluster case is covered by {@code RespClusterNodesTest} against real replies and by {@code
 * deploy/keydra-cluster.yaml} by hand: standing three servers up inside the test suite would cost
 * every run minutes to exercise a parser that already has its own tests.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class TopologyResourceTest {

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "topology-target",
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
    void describesAStandaloneTargetAsHavingNoNodes() {
        given().when()
                .get("/api/v1/connections/{id}/topology", connectionId)
                .then()
                .statusCode(200)
                .body("server.mode", equalTo("standalone"))
                // Not an error, and not an empty diagram: the target simply is not clustered.
                .body("nodes", hasSize(0))
                .body("sentinelMasters", hasSize(0));
    }

    @Test
    void asksTheServerWhatItSupportsRatherThanAssuming() {
        given().when()
                .get("/api/v1/connections/{id}/topology", connectionId)
                .then()
                .statusCode(200)
                .body("capabilities.detected", is(true))
                .body("capabilities.features", hasItem(Capabilities.Feature.COPY_KEY))
                .body("capabilities.features", hasItem(Capabilities.Feature.STREAMS))
                .body("capabilities.features", hasItem(Capabilities.Feature.SLOW_LOG));
    }

    @Test
    void doesNotClaimClusterOrSentinelForAStandaloneTarget() {
        // Every Redis has the CLUSTER command; only a clustered one has anything to say
        // through it, so having the command is not the same as being in the arrangement.
        given().when()
                .get("/api/v1/connections/{id}/topology", connectionId)
                .then()
                .statusCode(200)
                .body("capabilities.features", not(hasItem(Capabilities.Feature.CLUSTER)))
                .body("capabilities.features", not(hasItem(Capabilities.Feature.SENTINEL)));
    }

    @Test
    void reportsTheFlavorItDetected() {
        given().when()
                .get("/api/v1/connections/{id}/topology", connectionId)
                .then()
                .statusCode(200)
                .body("server.flavor", equalTo("redis"));
    }

    @Test
    void answers404ForAConnectionThatDoesNotExist() {
        given().when().get("/api/v1/connections/{id}/topology", 999999).then().statusCode(404);
    }
}
