package io.keydra.alerts.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.alerts.AlertFixtures;
import io.keydra.alerts.service.AlertEvaluator;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.engine.MetricsSample;
import io.keydra.monitoring.sink.RingSink;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Rules written against the past rather than against a number.
 *
 * <p>The readings are put into the ring by hand, which is what the sampler would have done on the
 * nights this is pretending happened. Everything after that is the real path: the same history
 * service a chart reads, the same evaluator, the same events.
 *
 * <p>What each test is really checking is that a rule which cannot be answered says nothing. An
 * alerting feature that guesses when it has no baseline is worse than one that has none: the guess
 * looks exactly like knowledge.
 */
@QuarkusTest
class BaselineRulesTest {

    private static final long QUIET = 1_000_000L;

    @Inject AlertEvaluator evaluator;
    @Inject RingSink ring;

    private Long target;

    @BeforeEach
    void setUp() {
        AlertFixtures.deleteEveryRule();
        ConnectionFixtures.deleteAllProfiles();
        // Nothing is listening there, for the reason the other alert tests give: a target that
        // answers would feed the rules real readings while these hand them made-up ones.
        target = ConnectionFixtures.createProfile("payments-cache", "127.0.0.1", 1);
    }

    @Test
    void aRuleComparesWithWhatTheMetricDidOverAnEarlierWindow() {
        // Half an hour of a quiet server, which is what "usual" means for this rule.
        Instant then = Instant.now().minus(Duration.ofMinutes(30));
        for (int minute = 0; minute < 10; minute++) {
            ring.write(target, reading(then.plus(Duration.ofMinutes(minute)), QUIET));
        }

        create(rule("Busier than usual", 140.0, 3600, 0));
        awaitBaseline();

        // Twice the usual, which is past a hundred and forty per cent of it.
        feed(reading(Instant.now(), QUIET * 2));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/alerts/events")
                                        .then()
                                        .body("", hasSize(1))
                                        .body("[0].kind", equalTo("FIRED"))
                                        .body("[0].reading", equalTo((float) (QUIET * 2)))
                                        // What it was measured against, not the percentage:
                                        // a history recording "140" would be a history of the
                                        // setting rather than of what happened.
                                        .body("[0].threshold", equalTo((float) (QUIET * 1.4))));
    }

    @Test
    void aReadingWithinTheUsualIsNotAnAlert() {
        Instant then = Instant.now().minus(Duration.ofMinutes(30));
        for (int minute = 0; minute < 10; minute++) {
            ring.write(target, reading(then.plus(Duration.ofMinutes(minute)), QUIET));
        }

        create(rule("Busier than usual", 140.0, 3600, 0));
        awaitBaseline();

        // A fifth above usual, which is inside what the rule allows.
        feed(reading(Instant.now(), (long) (QUIET * 1.2)));

        given().when().get("/api/v1/alerts").then().body("[0].state", equalTo("OK"));
        given().when().get("/api/v1/alerts/events").then().body("", hasSize(0));
    }

    @Test
    void aRuleWithNothingToCompareWithSaysNothing() {
        // No readings at all for this target: nothing was watching it last week either.
        create(rule("Busier than usual", 140.0, 3600, 0));

        feed(reading(Instant.now(), QUIET * 100));

        given().when()
                .get("/api/v1/alerts")
                .then()
                // The reading is shown — it was taken — and the rule stands where it was. A
                // rule that fired here would be firing on the absence of a comparison.
                .body("[0].reading", equalTo((float) (QUIET * 100)))
                .body("[0].state", equalTo("OK"))
                .body("[0].baseline", nullValue());
        given().when().get("/api/v1/alerts/events").then().body("", hasSize(0));
    }

    @Test
    void aWindowOlderThanAnythingKeptIsRefusedWhileSomebodyIsLooking() {
        Map<String, Object> lastWeek = rule("Busier than last week", 140.0, 3600, 7 * 24 * 3600);

        given().contentType(ContentType.JSON)
                .body(lastWeek)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(409)
                // Says what is wrong and what would fix it, in that order.
                .body("message", containsString("readings store"));
    }

    @Test
    void aMetricThatCannotBeAveragedIsRefused() {
        Map<String, Object> rate = rule("Evicting more than usual", 140.0, 3600, 0);
        rate.put("metric", "EVICTED_KEYS_PER_MINUTE");

        given().contentType(ContentType.JSON)
                .body(rate)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(409)
                .body("message", containsString("two readings"));

        Map<String, Object> silence = rule("Quieter than usual", 140.0, 3600, 0);
        silence.put("metric", "NO_ANSWER");

        given().contentType(ContentType.JSON)
                .body(silence)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(409)
                .body("message", containsString("percentage of"));
    }

    /** The list is also what asks for a baseline, so waiting on it is waiting for the figure. */
    private void awaitBaseline() {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/alerts")
                                        .then()
                                        .body("[0].baseline", notNullValue()));
    }

    private Map<String, Object> rule(String name, double percent, int window, int offset) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("connectionId", target);
        body.put("metric", "MEMORY_USED_BYTES");
        body.put("comparison", "ABOVE");
        body.put("basis", "BASELINE");
        body.put("threshold", percent);
        body.put("baselineWindowSeconds", window);
        body.put("baselineOffsetSeconds", offset);
        body.put("forSeconds", 0);
        body.put("enabled", true);
        return body;
    }

    private void create(Map<String, Object> body) {
        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(201);
    }

    private void feed(MetricsSample sample) {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Uni.createFrom()
                                    .item(
                                            () -> {
                                                evaluator.onReading(target, sample, null);
                                                return true;
                                            }));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not hand the evaluator a reading", failure);
        }
    }

    private static MetricsSample reading(Instant at, long usedBytes) {
        return new MetricsSample(
                at, usedBytes, usedBytes, 1000L, 3L, 5L, 100L, 8L, 2L, 42L, 900L, 0L, 0L);
    }
}
