package io.keydra.values.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reads and edits every value type against a real Redis. */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class ValuesTest {

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "values-target",
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

    private io.restassured.response.ValidatableResponse read(String key) {
        return given().when()
                .get("/api/v1/connections/{id}/value?key={key}", connectionId, key)
                .then()
                .statusCode(200);
    }

    private void mutate(Map<String, Object> body, long expectedAffected) {
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/connections/{id}/value", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo((int) expectedAffected));
    }

    @Test
    void readsAndWritesAString() {
        RedisTargetsResource.execRedis("SET", "greeting", "hello");

        read("greeting")
                .body("type", equalTo("string"))
                .body("value.text", equalTo("hello"))
                .body("value.encoding", equalTo("plain"))
                .body("value.size", equalTo(5));

        mutate(Map.of("operation", "setString", "key", "greeting", "value", "goodbye"), 1);
        read("greeting").body("value.text", equalTo("goodbye"));
    }

    @Test
    void prettyPrintsAJsonStringWithoutBeingAsked() {
        RedisTargetsResource.execRedis("SET", "doc", "{\"a\":1,\"b\":[2,3]}");

        read("doc")
                .body("value.encoding", equalTo("json"))
                // Pretty-printed, so a stored one-liner is readable.
                .body("value.text", containsString("\n"));
    }

    @Test
    void appliesARequestedEncoding() {
        RedisTargetsResource.execRedis("SET", "raw", "AB");

        given().when()
                .get("/api/v1/connections/{id}/value?key={key}&encoding=hex", connectionId, "raw")
                .then()
                .statusCode(200)
                .body("value.encoding", equalTo("hex"))
                .body("value.text", equalTo("41 42"));
    }

    @Test
    void readsAndEditsAHash() {
        RedisTargetsResource.execRedis("HSET", "user:1", "name", "alice", "age", "30");

        read("user:1")
                .body("type", equalTo("hash"))
                .body("total", equalTo(2))
                .body("fields", hasSize(2));

        mutate(
                Map.of(
                        "operation",
                        "setHashField",
                        "key",
                        "user:1",
                        "field",
                        "city",
                        "value",
                        "izmir"),
                1);
        read("user:1").body("total", equalTo(3));

        mutate(Map.of("operation", "deleteHashField", "key", "user:1", "field", "age"), 1);
        read("user:1").body("total", equalTo(2));
    }

    @Test
    void readsAListInIndexOrderAndEditsIt() {
        RedisTargetsResource.execRedis("RPUSH", "queue", "a", "b", "c");

        read("queue")
                .body("type", equalTo("list"))
                .body("total", equalTo(3))
                .body("elements.value.text", contains("a", "b", "c"))
                // Indices come back so an editor can address an element.
                .body("elements[0].index", equalTo(0));

        mutate(Map.of("operation", "setListElement", "key", "queue", "index", 1, "value", "B"), 1);
        read("queue").body("elements.value.text", contains("a", "B", "c"));

        mutate(
                Map.of(
                        "operation",
                        "pushListElement",
                        "key",
                        "queue",
                        "value",
                        "z",
                        "toHead",
                        true),
                4);
        read("queue").body("elements[0].value.text", equalTo("z"));

        mutate(
                Map.of("operation", "removeListElement", "key", "queue", "value", "z", "count", 1),
                1);
        read("queue").body("total", equalTo(3));
    }

    @Test
    void removesTheElementAtAnIndexRatherThanTheFirstOneLikeIt() {
        // The same text twice, which is the case removal by value cannot tell apart: LREM
        // takes the first match, so a button on the third row would remove the first.
        RedisTargetsResource.execRedis("RPUSH", "dupes", "a", "b", "a");

        mutate(Map.of("operation", "removeListElementAt", "key", "dupes", "index", 2), 1);

        read("dupes")
                .body("total", equalTo(2))
                .body("elements[0].value.text", equalTo("a"))
                .body("elements[1].value.text", equalTo("b"));
    }

    @Test
    void refusesToRemoveAnElementAtAnIndexThatIsNotThere() {
        RedisTargetsResource.execRedis("RPUSH", "short", "only");

        // The element somebody clicked is gone, and saying so is better than removing
        // whatever has since taken its place.
        given().contentType(ContentType.JSON)
                .body(Map.of("operation", "removeListElementAt", "key", "short", "index", 9))
                .when()
                .post("/api/v1/connections/{id}/value", connectionId)
                .then()
                .statusCode(500);

        read("short").body("total", equalTo(1));
    }

    @Test
    void readsAndEditsASet() {
        RedisTargetsResource.execRedis("SADD", "tags", "redis", "valkey");

        read("tags").body("type", equalTo("set")).body("total", equalTo(2));

        mutate(Map.of("operation", "addSetMember", "key", "tags", "member", "keydra"), 1);
        read("tags").body("total", equalTo(3));

        mutate(Map.of("operation", "removeSetMember", "key", "tags", "member", "redis"), 1);
        read("tags").body("total", equalTo(2));
    }

    @Test
    void readsAndEditsASortedSet() {
        RedisTargetsResource.execRedis("ZADD", "board", "10", "alice", "20", "bob");

        read("board")
                .body("type", equalTo("zset"))
                .body("total", equalTo(2))
                .body("members.score", notNullValue());

        mutate(
                Map.of(
                        "operation",
                        "addScoredMember",
                        "key",
                        "board",
                        "member",
                        "carol",
                        "score",
                        15.5),
                1);
        read("board").body("total", equalTo(3));

        mutate(Map.of("operation", "removeScoredMember", "key", "board", "member", "alice"), 1);
        read("board").body("total", equalTo(2));
    }

    @Test
    void readsAndEditsAStream() {
        RedisTargetsResource.execRedis("XADD", "events", "*", "kind", "created");

        read("events")
                .body("type", equalTo("stream"))
                .body("total", equalTo(1))
                .body("entries[0].fields[0].name", equalTo("kind"))
                .body("entries[0].fields[0].value.text", equalTo("created"));

        mutate(
                Map.of(
                        "operation", "addStreamEntry",
                        "key", "events",
                        "fields", Map.of("kind", "updated")),
                1);
        read("events").body("total", equalTo(2));
    }

    @Test
    void pagesALongHashAndTheCursorCoversAllOfIt() {
        // Above hash-max-listpack-entries (512 on Redis 8), so the hash is a real
        // hashtable and HSCAN honours COUNT. Below that threshold Redis returns a
        // listpack whole and ignores COUNT — a client must never assume a page size.
        String[] seed = new String[2 + 2 * 1000];
        seed[0] = "HSET";
        seed[1] = "big";
        for (int i = 0; i < 1000; i++) {
            seed[2 + 2 * i] = "field" + i;
            seed[3 + 2 * i] = "value" + i;
        }
        RedisTargetsResource.execRedis(seed);

        java.util.Set<String> seen = new java.util.HashSet<>();
        String cursor = "0";
        int pages = 0;
        do {
            var page =
                    given().when()
                            .get(
                                    "/api/v1/connections/{id}/value?key=big&count=100&cursor={c}",
                                    connectionId,
                                    cursor)
                            .then()
                            .statusCode(200)
                            .body("total", equalTo(1000))
                            .extract();
            List<String> names = page.path("fields.name");
            // A page, not the whole hash.
            org.hamcrest.MatcherAssert.assertThat(
                    names.size(), org.hamcrest.Matchers.lessThan(1000));
            seen.addAll(names);
            cursor = page.path("cursor");
            pages++;
        } while (cursor != null && !"0".equals(cursor) && pages < 100);

        org.hamcrest.MatcherAssert.assertThat(pages, org.hamcrest.Matchers.greaterThan(1));
        // Following the cursor to the end yields every field exactly once.
        org.hamcrest.MatcherAssert.assertThat(seen, hasSize(1000));
    }

    @Test
    void returns404ForAKeyThatDoesNotExist() {
        given().when()
                .get("/api/v1/connections/{id}/value?key=missing", connectionId)
                .then()
                .statusCode(404);
    }

    @Test
    void listsTheEncodingsAClientMayRequest() {
        given().when()
                .get("/api/v1/connections/{id}/value/encodings", connectionId)
                .then()
                .statusCode(200)
                .body(
                        "$",
                        org.hamcrest.Matchers.hasItems(
                                "plain", "json", "hex", "base64", "gzip", "msgpack"));
    }
}
