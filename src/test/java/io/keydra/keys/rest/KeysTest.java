package io.keydra.keys.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises key browsing against a real Redis.
 *
 * <p>The listing endpoint streams server-sent events, so these tests also pin the wire format the
 * UI consumes.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class KeysTest {

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId = createConnection();
        flushTarget();
        seed();
    }

    private static int createConnection() {
        return given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                "keys-target",
                                "host",
                                ConfigProvider.getConfig()
                                        .getValue(RedisTargetsResource.REDIS_HOST, String.class),
                                "port",
                                ConfigProvider.getConfig()
                                        .getValue(RedisTargetsResource.REDIS_PORT, Integer.class),
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

    /** Each test starts from an empty keyspace so counts are exact. */
    private void flushTarget() {
        RedisTargetsResource.flushRedis();
    }

    private void seed() {
        RedisTargetsResource.execRedis("SET", "user:1:profile", "alice");
        RedisTargetsResource.execRedis("SET", "user:2:profile", "bob");
        RedisTargetsResource.execRedis("SET", "cache:page:home", "html");
        RedisTargetsResource.execRedis("RPUSH", "cart:1:items", "sku-1");
        RedisTargetsResource.execRedis("SETEX", "session:1", "600", "token");
    }

    /**
     * Reads the stream and parses its events.
     *
     * <p>REST Assured has no deserialiser for text/event-stream, so the frames are split by hand.
     * That is a feature here: the test asserts against the exact bytes the browser receives.
     */
    private List<KeyEntry> scan(String query) {
        String body =
                given().accept("text/event-stream")
                        .when()
                        .get("/api/v1/connections/{id}/keys" + query, connectionId)
                        .then()
                        .statusCode(200)
                        .contentType(startsWith("text/event-stream"))
                        .extract()
                        .asString();

        ObjectMapper mapper = new ObjectMapper();
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .filter(json -> !json.isEmpty())
                .map(
                        json -> {
                            try {
                                return mapper.readValue(json, KeyEntry.class);
                            } catch (JsonProcessingException e) {
                                throw new IllegalStateException("Bad SSE frame: " + json, e);
                            }
                        })
                .toList();
    }

    @Test
    void streamsEveryKeyWithItsTypeAndTtl() {
        List<KeyEntry> keys = scan("");

        assertThat(keys, hasSize(5));
        assertThat(
                keys.stream().map(KeyEntry::key).toList(),
                containsInAnyOrder(
                        "user:1:profile",
                        "user:2:profile",
                        "cache:page:home",
                        "cart:1:items",
                        "session:1"));

        KeyEntry list =
                keys.stream().filter(k -> k.key().equals("cart:1:items")).findFirst().orElseThrow();
        assertThat(list.type(), equalTo("list"));
        assertThat(list.ttl(), equalTo(KeyEntry.NO_EXPIRY));

        KeyEntry session =
                keys.stream().filter(k -> k.key().equals("session:1")).findFirst().orElseThrow();
        assertThat(session.type(), equalTo("string"));
        assertThat(session.ttl(), greaterThan(0L));
    }

    @Test
    void filtersByGlob() {
        List<KeyEntry> keys = scan("?match=user:*");

        assertThat(keys, hasSize(2));
        assertThat(keys.stream().map(KeyEntry::key).toList(), everyItem(startsWith("user:")));
    }

    @Test
    void filtersByType() {
        List<KeyEntry> keys = scan("?type=list");

        assertThat(keys, hasSize(1));
        assertThat(keys.get(0).key(), equalTo("cart:1:items"));
    }

    @Test
    void buildsOneLevelOfTheNamespaceTree() {
        given().when()
                .get("/api/v1/connections/{id}/keys/tree", connectionId)
                .then()
                .statusCode(200)
                .body("name", containsInAnyOrder("user", "cache", "cart", "session"))
                .body("find { it.name == 'user' }.prefix", equalTo("user:"))
                .body("find { it.name == 'user' }.keyCount", equalTo(2))
                .body("find { it.name == 'user' }.hasChildren", equalTo(true))
                .body("find { it.name == 'session' }.hasChildren", equalTo(false));
    }

    @Test
    void expandsANestedPrefix() {
        given().when()
                .get("/api/v1/connections/{id}/keys/tree?prefix=user:", connectionId)
                .then()
                .statusCode(200)
                .body("name", containsInAnyOrder("1", "2"))
                .body("find { it.name == '1' }.prefix", equalTo("user:1:"));
    }

    @Test
    void deletesKeysInBulk() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("user:1:profile", "user:2:profile")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(2));

        assertThat(scan("").stream().map(KeyEntry::key).toList(), not(hasItem("user:1:profile")));
    }

    @Test
    void renamesAKeyButRefusesToOverwriteWithoutReplace() {
        given().contentType(ContentType.JSON)
                .body(Map.of("from", "user:1:profile", "to", "user:2:profile", "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/rename", connectionId)
                .then()
                .statusCode(200)
                // RENAMENX refuses because the target exists, so nothing is lost.
                .body("affected", equalTo(0));

        given().contentType(ContentType.JSON)
                .body(Map.of("from", "user:1:profile", "to", "user:3:profile", "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/rename", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));

        assertThat(scan("").stream().map(KeyEntry::key).toList(), hasItem("user:3:profile"));
    }

    @Test
    void copiesAKeyButRefusesToOverwriteWithoutReplace() {
        given().contentType(ContentType.JSON)
                .body(Map.of("from", "user:1:profile", "to", "user:2:profile", "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/copy", connectionId)
                .then()
                .statusCode(200)
                // The destination exists, so COPY declines rather than overwriting it.
                .body("affected", equalTo(0));

        given().contentType(ContentType.JSON)
                .body(Map.of("from", "user:1:profile", "to", "user:copy", "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/copy", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));

        List<String> names = scan("").stream().map(KeyEntry::key).toList();
        // A copy, not a move: the source is still there.
        assertThat(names, hasItem("user:copy"));
        assertThat(names, hasItem("user:1:profile"));
    }

    @Test
    void setsAndClearsATtl() {
        given().contentType(ContentType.JSON)
                .body(Map.of("key", "user:1:profile", "ttlSeconds", 120))
                .when()
                .post("/api/v1/connections/{id}/keys/expire", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));

        KeyEntry withTtl = scan("?match=user:1:profile").stream().findFirst().orElseThrow();
        assertThat(withTtl.ttl(), greaterThan(0L));

        // Omitting ttlSeconds clears the expiry.
        given().contentType(ContentType.JSON)
                .body(Map.of("key", "user:1:profile"))
                .when()
                .post("/api/v1/connections/{id}/keys/expire", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));

        KeyEntry persisted = scan("?match=user:1:profile").stream().findFirst().orElseThrow();
        assertThat(persisted.ttl(), equalTo(KeyEntry.NO_EXPIRY));
    }

    @Test
    void returns404ForAnUnknownConnection() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("whatever")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", 999999)
                .then()
                .statusCode(404);
    }

    @Test
    void purgeDeletesEverythingThePatternMatchesAndNothingElse() {
        RedisTargetsResource.execRedis("MSET", "gone:1", "a", "gone:2", "b", "kept:1", "c");

        given().contentType(ContentType.JSON)
                .body(Map.of("match", "gone:*"))
                .when()
                .post("/api/v1/connections/{id}/keys/purge", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(2));

        // The count alone would pass if the purge had deleted the wrong two keys.
        assertThat(scan("?match=gone:*"), empty());
        assertThat(RedisTargetsResource.execRedis("GET", "kept:1"), equalTo("c"));
    }

    @Test
    void purgeRefusesAnEmptyPatternRatherThanTakingItToMeanEverything() {
        // The most expensive typo in the application, so it is a validation failure and not
        // a default.
        given().contentType(ContentType.JSON)
                .body(Map.of("match", ""))
                .when()
                .post("/api/v1/connections/{id}/keys/purge", connectionId)
                .then()
                .statusCode(400);
    }

    @Test
    void browsesADatabaseOtherThanTheOneTheProfileOpensIn() {
        // A key in one database is invisible from another, which is exactly what makes the
        // switch worth having and exactly what a test of it has to show.
        RedisTargetsResource.execRedis("-n", "3", "SET", "elsewhere:1", "value");

        assertThat(scan("?match=elsewhere:*"), empty());

        assertThat(
                scan("?db=3&match=elsewhere:*").stream().map(KeyEntry::key).toList(),
                contains("elsewhere:1"));

        RedisTargetsResource.execRedis("-n", "3", "FLUSHDB");
    }

    @Test
    void writesLandInTheDatabaseTheRequestNamed() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("written:1")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete?db=4", connectionId)
                .then()
                .statusCode(200);

        RedisTargetsResource.execRedis("-n", "4", "SET", "written:1", "value");
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("written:1")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete?db=4", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));

        // The default database was never touched, which is the half that would go unnoticed.
        assertThat(RedisTargetsResource.execRedis("-n", "4", "EXISTS", "written:1"), equalTo("0"));
    }

    @Test
    void listsEveryDatabaseWithWhatIsInIt() {
        RedisTargetsResource.execRedis("-n", "5", "SET", "counted:1", "value");

        io.restassured.path.json.JsonPath databases =
                given().when()
                        .get("/api/v1/connections/{id}/databases", connectionId)
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath();

        // Every database the server is configured for, not only the ones holding something:
        // a list that hid the empty ones could not be used to move into one.
        assertThat(databases.getList("index", Integer.class), hasItem(0));
        assertThat(databases.getList("index", Integer.class).size(), greaterThan(1));
        assertThat(databases.getLong("find { it.index == 5 }.keys"), equalTo(1L));

        RedisTargetsResource.execRedis("-n", "5", "FLUSHDB");
    }
}
