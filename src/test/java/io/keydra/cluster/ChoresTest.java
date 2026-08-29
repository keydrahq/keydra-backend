package io.keydra.cluster;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.keydra.alerts.AlertFixtures;
import io.keydra.alerts.service.AlertEvaluator;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.engine.MetricsSample;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.schedule.ScheduleFixtures;
import io.keydra.schedule.service.JobScheduler;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.restassured.http.ContentType;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The work that must happen once, when there is more than one of these running.
 *
 * <p>Two instances behind a load balancer is how anything gets upgraded without going down, and
 * until this phase it meant the nightly flush ran twice and every alert was sent twice. Neither
 * announces itself: the second copy looks exactly like the first.
 *
 * <p>Tested by handing the lease to an instance that does not exist. That is the whole of what a
 * second instance is from here — one row saying somebody else holds it — so one process is enough
 * to test what two would do, and the part worth testing is that this one notices and stands down.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class ChoresTest {

    private static final Instant NOON = Instant.parse("2026-08-20T12:00:00Z");

    /** Three in the morning: the clock must not fire the schedule while the test is pressing it. */
    private static final String NIGHTLY = "0 3 * * *";

    @Inject AlertEvaluator evaluator;

    private Long target;
    private Long quiet;

    @BeforeEach
    void setUp() {
        ScheduleFixtures.deleteEverySchedule();
        AlertFixtures.deleteEveryRule();
        ConnectionFixtures.deleteAllProfiles();
        RedisTargetsResource.flushRedis();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        target = ConnectionFixtures.createProfile("nightly-cache", host, port);
        // The rule watches somewhere nothing is listening, for the reason the alert tests give:
        // a rule against a real server is fed real readings by the sampler it starts, and this
        // test is about who decides a rule rather than about how much memory a container uses.
        // Silence leaves a rule about a quantity exactly as it was, which is what makes the
        // reading handed over below the only one that counts.
        quiet = ConnectionFixtures.createProfile("nowhere", "127.0.0.1", 1);
    }

    @AfterEach
    void tearDown() {
        // Whatever the test did, this instance leaves holding the chores: everything else in the
        // suite is written for the single instance it is.
        ClusterFixtures.takeTheChoresBack();
    }

    @Test
    void aScheduleDoesNothingOnAnInstanceThatIsNotDoingTheChores() {
        RedisTargetsResource.execRedis("SET", "swept:1", "value");
        Integer id = createSchedule();

        ClusterFixtures.giveTheChoresAway();
        fireTheSchedule(id);

        // Waited out rather than checked once. The run is recorded asynchronously, so "no row
        // yet" a millisecond later would pass for a job that was about to run anyway.
        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/schedules/runs?jobId=" + id)
                                        .then()
                                        .body("", hasSize(0)));
        // The schedule is on this instance's clock all the same — a list that said "—" for the
        // next run on every instance but one would be worse than useless.
        given().when()
                .get("/api/v1/schedules")
                .then()
                .body("[0].nextRunAt", org.hamcrest.Matchers.notNullValue());

        ClusterFixtures.takeTheChoresBack();
        fireTheSchedule(id);

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/schedules/runs?jobId=" + id)
                                        .then()
                                        .body("", hasSize(1))
                                        .body("[0].outcome", equalTo("DONE")));
    }

    @Test
    void aRuleIsDecidedByTheInstanceDoingTheChoresAndNoOther() {
        createRule();

        ClusterFixtures.giveTheChoresAway();
        feed(reading(NOON, 800L));

        Awaitility.await()
                .during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/alerts/events")
                                        .then()
                                        .body("", hasSize(0)));
        given().when()
                .get("/api/v1/alerts")
                .then()
                // Not merely unsent: undecided. A follower keeping its own opinion of where a
                // rule stands would be a second answer to a question with one.
                .body("[0].state", equalTo("OK"));

        ClusterFixtures.takeTheChoresBack();
        feed(reading(NOON.plusSeconds(5), 900L));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/alerts/events")
                                        .then()
                                        .body("", hasSize(1))
                                        .body("[0].kind", equalTo("FIRED")));
    }

    private Integer createSchedule() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Nightly sweep");
        body.put("connectionId", target);
        body.put("jobType", "FLUSH_DATABASE");
        body.put("cron", NIGHTLY);
        body.put("enabled", true);
        body.put("settings", "{\"match\":\"swept:*\"}");
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/schedules?connectionId=" + target)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private void createRule() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Memory");
        body.put("connectionId", quiet);
        body.put("metric", "MEMORY_USED_BYTES");
        body.put("comparison", "ABOVE");
        body.put("threshold", 500.0);
        body.put("forSeconds", 0);
        body.put("enabled", true);
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alerts?connectionId=" + quiet)
                .then()
                .statusCode(201);
    }

    /** Hands the schedule over the way the clock does: on an event loop, on its own context. */
    private static void fireTheSchedule(Integer jobId) {
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

    private void feed(MetricsSample sample) {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Uni.createFrom()
                                    .item(
                                            () -> {
                                                evaluator.onReading(quiet, sample, null);
                                                return true;
                                            }));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not hand the evaluator a reading", failure);
        }
    }

    private static MetricsSample reading(Instant at, Long usedBytes) {
        return new MetricsSample(
                at, usedBytes, usedBytes, 1000L, 3L, 5L, 100L, 8L, 2L, 42L, 900L, 0L, 0L);
    }
}
