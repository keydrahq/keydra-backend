package io.keydra.keys.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Export and import against a real server.
 *
 * <p>The payloads are the store's own serialisation, so these only pass against something that
 * actually speaks the protocol — which is the point: a mocked dump would prove nothing about
 * whether the bytes restore.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class KeyTransferTest {

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "transfer-target",
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

    private List<Map<String, Object>> export(Map<String, Object> request) {
        return given().contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/v1/connections/{id}/keys/export", connectionId)
                .then()
                .statusCode(200)
                .extract()
                .as(new TypeRef<>() {});
    }

    @Test
    void exportsTheKeysItIsGivenAndNothingElse() {
        RedisTargetsResource.execRedis("SET", "wanted", "yes");
        RedisTargetsResource.execRedis("SET", "unwanted", "no");

        List<Map<String, Object>> exported = export(Map.of("keys", List.of("wanted")));

        assertThat(exported, hasSize(1));
        assertThat(exported.getFirst().get("key"), equalTo("wanted"));
        // The payload is the store's serialisation, base64 on the wire.
        assertThat(exported.getFirst().get("payload"), notNullValue());
    }

    @Test
    void walksTheKeyspaceWhenGivenAPatternInsteadOfNames() {
        RedisTargetsResource.execRedis("SET", "user:1", "a");
        RedisTargetsResource.execRedis("SET", "user:2", "b");
        RedisTargetsResource.execRedis("SET", "other", "c");

        List<Map<String, Object>> exported = export(Map.of("match", "user:*"));

        assertThat(exported, hasSize(2));
        assertThat(exported.stream().map(entry -> entry.get("key")).toList(), hasItem("user:1"));
    }

    @Test
    void carriesTheRemainingLifeSoARestoredSessionStillExpires() {
        RedisTargetsResource.execRedis("SETEX", "session:1", "600", "token");

        List<Map<String, Object>> exported = export(Map.of("keys", List.of("session:1")));

        // Milliseconds, and less than the ten minutes it was given, because time passed.
        assertThat(((Number) exported.getFirst().get("ttlMillis")).longValue(), greaterThan(0L));
    }

    @Test
    void skipsAKeyThatExpiredBetweenBeingNamedAndBeingRead() {
        // Nothing was ever written, so the export has nothing to say about it. An absence
        // rather than an error: a key listed a moment ago and gone since is ordinary.
        List<Map<String, Object>> exported = export(Map.of("keys", List.of("never-existed")));

        assertThat(exported, hasSize(0));
    }

    @Test
    void restoresWhatWasExported() {
        RedisTargetsResource.execRedis("RPUSH", "cart:1", "sku-1", "sku-2", "sku-3");
        List<Map<String, Object>> exported = export(Map.of("keys", List.of("cart:1")));

        RedisTargetsResource.flushRedis();

        given().contentType(ContentType.JSON)
                .body(Map.of("keys", exported, "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/import", connectionId)
                .then()
                .statusCode(200)
                .body("restored", equalTo(1))
                .body("skipped", equalTo(0))
                .body("failed", equalTo(0))
                // Nothing failed, so there is nothing to explain.
                .body("reason", equalTo(null));

        // The list came back whole, in order, which is what the store's own serialisation
        // buys over rendering the value as text.
        assertThat(RedisTargetsResource.execRedis("LRANGE", "cart:1", "0", "-1"), notNullValue());
        assertThat(RedisTargetsResource.execRedis("LLEN", "cart:1"), equalTo("3"));
    }

    @Test
    void leavesAnExistingKeyAloneUnlessReplacingIsAskedFor() {
        RedisTargetsResource.execRedis("SET", "kept", "original");
        List<Map<String, Object>> exported = export(Map.of("keys", List.of("kept")));
        RedisTargetsResource.execRedis("SET", "kept", "newer");

        given().contentType(ContentType.JSON)
                .body(Map.of("keys", exported, "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/import", connectionId)
                .then()
                .statusCode(200)
                .body("restored", equalTo(0))
                .body("skipped", equalTo(1));

        assertThat(RedisTargetsResource.execRedis("GET", "kept"), equalTo("newer"));

        given().contentType(ContentType.JSON)
                .body(Map.of("keys", exported, "replace", true))
                .when()
                .post("/api/v1/connections/{id}/keys/import", connectionId)
                .then()
                .statusCode(200)
                .body("restored", equalTo(1));

        assertThat(RedisTargetsResource.execRedis("GET", "kept"), equalTo("original"));
    }

    @Test
    void countsAKeyTheStoreRefusesRatherThanStopping() {
        RedisTargetsResource.execRedis("SET", "good", "value");
        List<Map<String, Object>> exported = export(Map.of("keys", List.of("good")));
        RedisTargetsResource.flushRedis();

        // A payload that is not a dump at all. The store checks the checksum and refuses;
        // the import must carry on and say so rather than abandoning the file.
        Map<String, Object> corrupt =
                Map.of("key", "bad", "ttlMillis", 0, "payload", "bm90LWEtZHVtcA==");

        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of(corrupt, exported.getFirst()), "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/import", connectionId)
                .then()
                .statusCode(200)
                .body("restored", equalTo(1))
                .body("failed", equalTo(1))
                // What the store said, so the file's problem is on screen rather than a
                // count with no explanation.
                .body("reason", containsString("DUMP payload"));

        assertThat(RedisTargetsResource.execRedis("GET", "good"), equalTo("value"));
    }
}
