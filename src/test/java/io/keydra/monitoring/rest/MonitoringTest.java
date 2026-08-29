package io.keydra.monitoring.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Statistics come from a real server, so this reads a real one. */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class MonitoringTest {

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "monitored",
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
        RedisTargetsResource.flushRedis();
    }

    @AfterEach
    void stopSampling() {
        given().when().delete("/api/v1/connections/{id}/monitoring", connectionId);
    }

    @Test
    void samplesNothingUntilAsked() {
        // Sampling costs a round trip per interval, so it never starts on its own.
        given().when()
                .get("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false))
                .body("samples.size()", equalTo(0));
    }

    @Test
    void takesTheFirstReadingImmediately() {
        given().when()
                .post("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true))
                // A dashboard showing nothing for the first interval looks broken.
                .body("samples.size()", greaterThanOrEqualTo(1))
                .body("samples[0].memoryUsedBytes", greaterThan(0))
                .body("samples[0].at", notNullValue());
    }

    @Test
    void reportsNoMemoryCeilingRatherThanACeilingOfZero() {
        // maxmemory 0 means "no limit"; reporting it as a limit of zero would read as full.
        given().when()
                .post("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(200)
                .body("samples[0].memoryMaxBytes", equalTo(null));
    }

    @Test
    void countsTheKeysInTheWatchedDatabase() {
        RedisTargetsResource.execRedis("MSET", "a", "1", "b", "2", "c", "3");

        given().when()
                .post("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(200)
                .body("samples[0].keyCount", equalTo(3));
    }

    @Test
    void stopsWhenAskedAndSaysSoWhenThereIsNothingToStop() {
        given().when()
                .post("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(200);

        given().when()
                .delete("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(204);

        given().when()
                .delete("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(404);
    }

    @Test
    void readsOneSampleWithoutStartingASampler() {
        // The connection list draws a summary per target and has no use for a sampler
        // running behind it, so a single reading must be available on its own.
        RedisTargetsResource.execRedis("MSET", "a", "1", "b", "2");

        given().when()
                .get("/api/v1/connections/{id}/monitoring/sample", connectionId)
                .then()
                .statusCode(200)
                .body("keyCount", equalTo(2))
                .body("memoryUsedBytes", greaterThan(0))
                .body("at", notNullValue());

        given().when()
                .get("/api/v1/connections/{id}/monitoring", connectionId)
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false));
    }

    @Test
    void returnsRawStatisticsGroupedBySection() {
        given().when()
                .get("/api/v1/connections/{id}/monitoring/info", connectionId)
                .then()
                .statusCode(200)
                .body("$", hasKey("server"))
                .body("server", hasKey("redis_version"));
    }

    @Test
    void narrowsStatisticsToOneSection() {
        given().when()
                .get("/api/v1/connections/{id}/monitoring/info?section=memory", connectionId)
                .then()
                .statusCode(200)
                .body("$", hasKey("memory"))
                .body("$", org.hamcrest.Matchers.not(hasKey("keyspace")));
    }

    @Test
    void recordsAndClearsSlowCommands() {
        // Anything at all is slow when the threshold is zero microseconds.
        RedisTargetsResource.execRedis("CONFIG", "SET", "slowlog-log-slower-than", "0");
        RedisTargetsResource.execRedis("SET", "slow", "value");

        given().when()
                .get("/api/v1/connections/{id}/monitoring/slowlog", connectionId)
                .then()
                .statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].arguments", notNullValue())
                .body("[0].durationMicros", greaterThanOrEqualTo(0));

        given().when()
                .delete("/api/v1/connections/{id}/monitoring/slowlog", connectionId)
                .then()
                .statusCode(204);

        RedisTargetsResource.execRedis("CONFIG", "SET", "slowlog-log-slower-than", "10000");
    }

    @Test
    void listsAttachedClients() {
        given().when()
                .get("/api/v1/connections/{id}/monitoring/clients", connectionId)
                .then()
                .statusCode(200)
                // Keydra's own connection is one of them, so there is always at least one.
                .body("size()", greaterThan(0))
                .body("[0].id", notNullValue())
                .body("[0].address", notNullValue());
    }

    @Test
    void saysSoWhenThereIsNoSuchClientToKill() {
        given().when()
                .delete(
                        "/api/v1/connections/{id}/monitoring/clients/{client}",
                        connectionId,
                        "999999")
                .then()
                .statusCode(404);
    }

    @Test
    void ranksTheLargestKeysInASample() {
        RedisTargetsResource.execRedis("SET", "small", "x");
        RedisTargetsResource.execRedis("SET", "large", "x".repeat(20000));

        given().when()
                .get("/api/v1/connections/{id}/monitoring/big-keys?sample=100&top=5", connectionId)
                .then()
                .statusCode(200)
                // The report says what it was drawn from: a ranking without that is a claim
                // about the whole keyspace, which it is not.
                .body("sampled", greaterThanOrEqualTo(2))
                .body("totalBytes", greaterThan(0))
                .body("largest[0].key", equalTo("large"))
                .body("largest[0].type", equalTo("string"));
    }
}
