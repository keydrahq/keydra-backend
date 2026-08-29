package io.keydra.keys.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.resources.RedisTargetsResource.Target;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Moving keys from one running server to another.
 *
 * <p>Both ends are real: the test resource starts a Redis and a Valkey, and a migration that only
 * ever ran against one store would prove nothing about the case this feature exists for.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class KeyMigrationTest {

    private int sourceId;
    private int targetId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        sourceId =
                createConnection(
                        "migration-source",
                        RedisTargetsResource.REDIS_HOST,
                        RedisTargetsResource.REDIS_PORT);
        // A second profile pointed at the same server, which is what most of these tests
        // want: they are about what a migration does, not about which store is at each end.
        // The one below that crosses stores uses Valkey.
        targetId =
                createConnection(
                        "migration-target",
                        RedisTargetsResource.REDIS_HOST,
                        RedisTargetsResource.REDIS_PORT);
        RedisTargetsResource.flushRedis();
        RedisTargetsResource.flushValkey();
    }

    private static int createConnection(String name, String hostKey, String portKey) {
        return createConnection(name, hostKey, portKey, 0);
    }

    private static int createConnection(String name, String hostKey, String portKey, int database) {
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
                                database,
                                "type",
                                "STANDALONE"))
                .when()
                .post("/api/v1/connections")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String startMigration(Map<String, Object> request) {
        return startMigrationFrom(sourceId, request);
    }

    private String startMigrationFrom(int from, Map<String, Object> request) {
        return given().contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/v1/connections/{id}/keys/migrate", from)
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("state", equalTo("RUNNING"))
                .extract()
                .path("id");
    }

    /** Waits for the job to leave RUNNING, then answers its final state. */
    private io.restassured.path.json.JsonPath awaitFinished(String jobId) {
        return awaitFinishedFor(sourceId, jobId);
    }

    private io.restassured.path.json.JsonPath awaitFinishedFor(int from, String jobId) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .until(
                        () ->
                                !"RUNNING"
                                        .equals(
                                                given().when()
                                                        .get(
                                                                "/api/v1/connections/{id}/keys/migrate",
                                                                from)
                                                        .then()
                                                        .statusCode(200)
                                                        .extract()
                                                        .jsonPath()
                                                        .getString(
                                                                "find { it.id == '"
                                                                        + jobId
                                                                        + "' }.state")));
        return given().when()
                .get("/api/v1/connections/{id}/keys/migrate", from)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    @Test
    void movesEveryKeyAPatternMatchesWithoutOverrunningTheConnectionPool() {
        // More keys than one batch, and more than the client's connection queue is deep.
        // Both matter: the first proves the walk carries on past a batch boundary, and the
        // second is the failure this test was written for — a batch restored with a wider
        // fan-out than the pool would hold came back as "the target refused these keys",
        // which is what a full queue looks like from the outside.
        seed(201);

        String jobId =
                startMigration(
                        Map.of(
                                "targetConnectionId",
                                targetId,
                                "match",
                                "wanted:*",
                                "replace",
                                true));
        var jobs = awaitFinished(jobId);

        String prefix = "find { it.id == '" + jobId + "' }.";
        assertThat(jobs.getString(prefix + "state"), equalTo("DONE"));
        assertThat(jobs.getLong(prefix + "scanned"), greaterThanOrEqualTo(201L));
        assertThat(jobs.getLong(prefix + "migrated"), equalTo(201L));
        assertThat(jobs.getLong(prefix + "failed"), equalTo(0L));
    }

    /** Writes n keys in one command: two hundred round trips through docker exec is a minute. */
    private static void seed(int count) {
        String[] command = new String[count * 2 + 1];
        command[0] = "MSET";
        for (int i = 0; i < count; i++) {
            command[i * 2 + 1] = "wanted:" + i;
            command[i * 2 + 2] = "value-" + i;
        }
        RedisTargetsResource.execRedis(command);
    }

    @Test
    void refusesToMigrateATargetIntoItself() {
        given().contentType(ContentType.JSON)
                .body(Map.of("targetConnectionId", sourceId, "match", "*"))
                .when()
                .post("/api/v1/connections/{id}/keys/migrate", sourceId)
                .then()
                .statusCode(409);
    }

    @Test
    void leavesTheSourceAloneUnlessAskedToEmptyIt() {
        RedisTargetsResource.execRedis("SET", "moved:1", "value");

        String jobId =
                startMigration(
                        Map.of(
                                "targetConnectionId",
                                targetId,
                                "match",
                                "moved:*",
                                "replace",
                                true,
                                "deleteFromSource",
                                false));
        awaitFinished(jobId);

        assertThat(RedisTargetsResource.execRedis("GET", "moved:1"), equalTo("value"));
    }

    @Test
    void countsWhatTheTargetWouldNotOverwrite() {
        RedisTargetsResource.execRedis("SET", "kept:1", "original");

        // Source and target are the same server, so the key is already on the target and
        // without replace the migration must report it as skipped rather than as moved.
        String jobId =
                startMigration(
                        Map.of(
                                "targetConnectionId",
                                targetId,
                                "match",
                                "kept:*",
                                "replace",
                                false));
        var jobs = awaitFinished(jobId);

        String prefix = "find { it.id == '" + jobId + "' }.";
        assertThat(jobs.getLong(prefix + "skipped"), equalTo(1L));
        assertThat(jobs.getLong(prefix + "migrated"), equalTo(0L));
    }

    @Test
    void aMigrationThatFindsNothingStillFinishes() {
        // The case somebody hits by typing a prefix that does not exist. A job that stayed
        // "running" forever would be indistinguishable from one that is still working, which
        // is the one thing the state column has to get right.
        String jobId =
                startMigration(
                        Map.of("targetConnectionId", targetId, "match", "nothing-matches-this:*"));

        var jobs = awaitFinished(jobId);

        String prefix = "find { it.id == '" + jobId + "' }.";
        assertThat(jobs.getString(prefix + "state"), equalTo("DONE"));
        assertThat(jobs.getLong(prefix + "scanned"), equalTo(0L));
    }

    @Test
    void aFinishedMigrationIsWrittenDownRatherThanRemembered() {
        RedisTargetsResource.execRedis("SET", "kept:1", "original");
        String jobId = startMigration(Map.of("targetConnectionId", targetId, "match", "kept:*"));
        awaitFinished(jobId);

        // Read back through a fresh request, and from the row rather than from the map the
        // job ran in: this is the part a restart used to lose.
        given().when()
                .get("/api/v1/migrations")
                .then()
                .statusCode(200)
                .body("find { it.id == '" + jobId + "' }.state", equalTo("DONE"))
                .body("find { it.id == '" + jobId + "' }.startedAt", notNullValue())
                .body("find { it.id == '" + jobId + "' }.finishedAt", notNullValue());
    }

    @Test
    void answersNotFoundWhenStoppingAJobThatIsNotRunning() {
        given().when()
                .delete("/api/v1/connections/{id}/keys/migrate/{job}", sourceId, "no-such-job")
                .then()
                .statusCode(404);
    }

    /**
     * Every direction, because the two ends are not interchangeable.
     *
     * <p>Redis 8 stamps its dumps with an RDB version Valkey 9 refuses outright, so the two crossed
     * pairs can only move a key by reading its value and writing it back, while the two same-store
     * pairs take the fast path. Both paths have to produce the same keyspace, and only running all
     * four says whether they do.
     *
     * <p>The same-store pairs move between databases rather than onto themselves: copying a key
     * over itself would pass whether or not anything was written.
     */
    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"REDIS,VALKEY", "VALKEY,REDIS", "REDIS,REDIS", "VALKEY,VALKEY"})
    void copiesEveryShapeInEitherDirection(Target from, Target to) {
        int database = from == to ? 1 : 0;
        RedisTargetsResource.flush(from, 0);
        RedisTargetsResource.flush(to, database);

        int fromId = createConnection("from-" + from + "-" + to, from.hostKey(), from.portKey(), 0);
        int toId = createConnection("to-" + from + "-" + to, to.hostKey(), to.portKey(), database);

        RedisTargetsResource.exec(from, "SET", "shape:string", "hello");
        RedisTargetsResource.exec(from, "EXPIRE", "shape:string", "600");
        RedisTargetsResource.exec(from, "RPUSH", "shape:list", "a", "b", "c");
        RedisTargetsResource.exec(from, "SADD", "shape:set", "x", "y");
        RedisTargetsResource.exec(from, "ZADD", "shape:zset", "1", "one", "2", "two");
        RedisTargetsResource.exec(from, "HSET", "shape:hash", "f1", "a", "f2", "b");
        RedisTargetsResource.exec(from, "XADD", "shape:stream", "5-5", "f", "v");

        String jobId =
                startMigrationFrom(
                        fromId,
                        Map.of(
                                "targetConnectionId",
                                toId,
                                "match",
                                "shape:*",
                                "replace",
                                true,
                                "deleteFromSource",
                                false));
        io.restassured.path.json.JsonPath finished = awaitFinishedFor(fromId, jobId);
        String job = "find { it.id == '" + jobId + "' }.";

        assertThat(finished.getString(job + "state"), equalTo("DONE"));
        assertThat(finished.getInt(job + "migrated"), equalTo(6));
        assertThat(finished.getInt(job + "failed"), equalTo(0));

        String db = String.valueOf(database);
        assertThat(
                RedisTargetsResource.exec(to, "-n", db, "GET", "shape:string"), equalTo("hello"));
        // Order is part of what a list is, so it is asserted rather than the membership.
        assertThat(
                RedisTargetsResource.exec(to, "-n", db, "LRANGE", "shape:list", "0", "-1"),
                equalTo("a\nb\nc"));
        assertThat(RedisTargetsResource.exec(to, "-n", db, "SCARD", "shape:set"), equalTo("2"));
        assertThat(
                RedisTargetsResource.exec(to, "-n", db, "ZSCORE", "shape:zset", "two"),
                equalTo("2"));
        assertThat(
                RedisTargetsResource.exec(to, "-n", db, "HGET", "shape:hash", "f2"), equalTo("b"));
        // An entry's id is part of the entry — consumers remember where they were by it —
        // so a copy that let the target mint new ones would not be a copy.
        assertThat(
                RedisTargetsResource.exec(to, "-n", db, "XRANGE", "shape:stream", "-", "+"),
                containsString("5-5"));
        // A session copied without its expiry is a session that never expires, which is the
        // one way this feature could quietly do harm.
        int remaining =
                Integer.parseInt(RedisTargetsResource.exec(to, "-n", db, "TTL", "shape:string"));
        assertThat(remaining, greaterThan(0));
        assertThat(remaining, lessThanOrEqualTo(600));
    }
}
