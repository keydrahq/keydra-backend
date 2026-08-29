package io.keydra.schedule.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.schedule.ScheduleFixtures;
import io.keydra.schedule.service.JobScheduler;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.restassured.http.ContentType;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Work arranged to happen on its own.
 *
 * <p>Against a real server, because the point of a schedule is that it does the thing: a test that
 * only checked rows would pass for a schedule that saves perfectly and empties nothing.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class SchedulesTest {

    /** Every minute. Never actually waited for — "run now" is what these tests press. */
    private static final String EVERY_MINUTE = "*/1 * * * *";

    private Long target;
    private Long elsewhere;

    @BeforeEach
    void setUp() {
        ScheduleFixtures.deleteEverySchedule();
        ConnectionFixtures.deleteAllProfiles();
        RedisTargetsResource.flushRedis();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        target = ConnectionFixtures.createProfile("nightly-cache", host, port);
        elsewhere = ConnectionFixtures.createProfile("somewhere-else", host, port);
    }

    @Test
    void aNewScheduleSaysWhenItRunsNext() {
        Integer id = create(request("Empty the cache", "FLUSH_DATABASE", "{\"match\":\"*\"}"));

        given().when()
                .get("/api/v1/schedules")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].id", equalTo(id))
                .body("[0].name", equalTo("Empty the cache"))
                .body("[0].connectionName", equalTo("nightly-cache"))
                // The question a schedule list is opened with, and it is the scheduler's own
                // answer rather than something worked out a second way here.
                .body("[0].nextRunAt", notNullValue())
                .body("[0].lastOutcome", nullValue());
    }

    @Test
    void theJobTypesSayWhatEachOneNeeds() {
        given().when()
                .get("/api/v1/schedules/job-types")
                .then()
                .statusCode(200)
                .body("name", contains("FLUSH_DATABASE", "COPY_KEYS", "EXPORT_KEYS"))
                // Named the way /auth/permissions names them, so an interface can compare the
                // two without a translation table in the middle.
                .body("find { it.name == 'FLUSH_DATABASE' }.requires", equalTo("KEYS_DELETE"));
    }

    @Test
    void anExpressionTheSchedulerCannotReadIsRefused() {
        Map<String, Object> body = request("Nonsense", "FLUSH_DATABASE", "{}");
        body.put("cron", "not a cron expression");

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/schedules?connectionId=" + target)
                .then()
                .statusCode(409)
                .body("message", containsString("not a cron expression"));

        given().when().get("/api/v1/schedules").then().statusCode(200).body("", hasSize(0));
    }

    @Test
    void settingsTheHandlerCannotUseAreRefusedWhileSomebodyIsLooking() {
        // A copy to itself: refused by the handler that would have to run it, not by a second
        // opinion written here.
        given().contentType(ContentType.JSON)
                .body(
                        request(
                                "Copy to myself",
                                "COPY_KEYS",
                                "{\"targetConnectionId\":" + target + "}"))
                .when()
                .post("/api/v1/schedules?connectionId=" + target)
                .then()
                .statusCode(409);

        // A file name that is a path. The export directory is the deployment's to choose, and
        // a name in a row is not a way to write outside it.
        given().contentType(ContentType.JSON)
                .body(
                        request(
                                "Escape the directory",
                                "EXPORT_KEYS",
                                "{\"filePrefix\":\"../../etc/passwd\"}"))
                .when()
                .post("/api/v1/schedules?connectionId=" + target)
                .then()
                .statusCode(409);
    }

    @Test
    void aCopyToAnotherTargetIsAccepted() {
        create(
                request(
                        "Nightly copy",
                        "COPY_KEYS",
                        "{\"targetConnectionId\":" + elsewhere + ",\"match\":\"*\"}"));

        given().when()
                .get("/api/v1/schedules")
                .then()
                .statusCode(200)
                .body("[0].jobType", equalTo("COPY_KEYS"));
    }

    @Test
    void runningItNowRemovesWhatItMatchesAndLeavesTheRest() {
        RedisTargetsResource.execRedis("MSET", "session:a", "1", "session:b", "2", "keep:me", "3");

        Integer id =
                create(request("Drop sessions", "FLUSH_DATABASE", "{\"match\":\"session:*\"}"));

        given().when()
                .post("/api/v1/schedules/" + id + "/run?connectionId=" + target)
                .then()
                .statusCode(200)
                .body("outcome", equalTo("DONE"))
                // Recorded as a manual run, so a history can tell one nobody expected from one
                // the clock asked for.
                .body("wasManual", equalTo(true))
                .body("detail", containsString("2"));

        // The one that did not match is still there. A flush that took everything would pass a
        // test that only counted what it removed. Asked of the server rather than of Keydra,
        // because what matters is what is in the keyspace.
        assertThat(RedisTargetsResource.execRedis("EXISTS", "keep:me").trim(), equalTo("1"));
        assertThat(RedisTargetsResource.execRedis("EXISTS", "session:a").trim(), equalTo("0"));
    }

    @Test
    void whatItDidIsKeptAndTheScheduleRemembersHowItWent() {
        Integer id = create(request("Drop nothing", "FLUSH_DATABASE", "{\"match\":\"absent:*\"}"));

        given().when()
                .post("/api/v1/schedules/" + id + "/run?connectionId=" + target)
                .then()
                .statusCode(200);
        given().when()
                .post("/api/v1/schedules/" + id + "/run?connectionId=" + target)
                .then()
                .statusCode(200);

        given().when()
                .get("/api/v1/schedules/runs?jobId=" + id)
                .then()
                .statusCode(200)
                .body("", hasSize(2))
                .body("[0].jobName", equalTo("Drop nothing"))
                .body("[0].outcome", equalTo("DONE"))
                .body("[0].finishedAt", notNullValue());

        given().when()
                .get("/api/v1/schedules")
                .then()
                .statusCode(200)
                .body("[0].lastOutcome", equalTo("DONE"))
                .body("[0].lastRunAt", notNullValue());
    }

    @Test
    void oneThatIsTurnedOffIsOffTheClockButStillRunsByHand() {
        Integer id = create(request("Paused", "FLUSH_DATABASE", "{\"match\":\"absent:*\"}"));

        Map<String, Object> paused =
                request("Paused", "FLUSH_DATABASE", "{\"match\":\"absent:*\"}");
        paused.put("enabled", false);

        given().contentType(ContentType.JSON)
                .body(paused)
                .when()
                .put("/api/v1/schedules/" + id + "?connectionId=" + target)
                .then()
                .statusCode(200)
                .body("enabled", equalTo(false))
                // Nothing is due, because nothing is on the clock.
                .body("nextRunAt", nullValue());

        given().when()
                .post("/api/v1/schedules/" + id + "/run?connectionId=" + target)
                .then()
                .statusCode(200)
                .body("outcome", equalTo("DONE"));
    }

    @Test
    void removingItTakesTheRecordOfWhatItDidWithIt() {
        Integer id = create(request("Temporary", "FLUSH_DATABASE", "{\"match\":\"absent:*\"}"));
        given().when()
                .post("/api/v1/schedules/" + id + "/run?connectionId=" + target)
                .then()
                .statusCode(200);

        given().when()
                .delete("/api/v1/schedules/" + id + "?connectionId=" + target)
                .then()
                .statusCode(204);

        given().when().get("/api/v1/schedules").then().statusCode(200).body("", hasSize(0));
        // A run whose schedule is gone answers nothing, so it goes with it rather than
        // becoming a row nobody can trace.
        given().when()
                .get("/api/v1/schedules/runs?jobId=" + id)
                .then()
                .statusCode(200)
                .body("", hasSize(0));
    }

    private Map<String, Object> request(String name, String jobType, String settings) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("connectionId", target);
        body.put("jobType", jobType);
        body.put("cron", EVERY_MINUTE);
        body.put("enabled", true);
        body.put("settings", settings);
        return body;
    }

    @Test
    void aScheduleRunsWhenTheClockAsksAndNotOnlyWhenSomebodyPresses() {
        RedisTargetsResource.execRedis("SET", "swept:1", "value");
        Map<String, Object> nightly =
                request("Nightly sweep", "FLUSH_DATABASE", "{\"match\":\"swept:*\"}");
        // Three in the morning, so the clock cannot fire this while the test is running and
        // turn one run into two. What is being tested is the thread the scheduler hands the
        // work to, not whether cron can count.
        nightly.put("cron", "0 3 * * *");
        Integer id = create(nightly);

        // Run the way the scheduler runs it — from an event loop, which is a thread that may
        // not be blocked. This is not a detail: the first version of this waited for the work
        // on that thread, failed on every scheduled run, and said so only in a log. "Run now"
        // went on working, so the page showed a schedule that had never once fired.
        onEventLoop(id);

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/schedules/runs?jobId=" + id)
                                        .then()
                                        .body("", hasSize(1))
                                        .body("[0].outcome", equalTo("DONE"))
                                        .body("[0].wasManual", equalTo(false)));

        assertThat(RedisTargetsResource.execRedis("EXISTS", "swept:1").trim(), equalTo("0"));
    }

    /**
     * Hands the schedule to the runner the way the scheduler hands it over.
     *
     * <p>A duplicated context on an event loop, which is both halves of what makes this a test of
     * anything: duplicated because that is what a reactive session needs, and an event loop because
     * that is the thread that may not be blocked — the one the version before this one blocked.
     */
    private static void onEventLoop(Integer jobId) {
        JobScheduler scheduler = Arc.container().instance(JobScheduler.class).get();
        Vertx vertx = Arc.container().instance(Vertx.class).get();
        Context context = VertxContext.getOrCreateDuplicatedContext(vertx);
        VertxContextSafetyToggle.setContextSafe(context, true);
        context.runOnContext(
                ignored ->
                        scheduler
                                .runFor(Long.valueOf(jobId))
                                .subscribe()
                                .with(done -> {}, failure -> {}));
    }

    private Integer create(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/schedules?connectionId=" + body.get("connectionId"))
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
