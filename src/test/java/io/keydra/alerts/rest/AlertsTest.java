package io.keydra.alerts.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.alerts.AlertFixtures;
import io.keydra.alerts.service.AlertEvaluator;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.engine.MetricsSample;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
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
 * Rules that watch, and the three states they move between.
 *
 * <p>Driven by handing the evaluator readings rather than by waiting for a server to fill up: the
 * thing under test is the decision, and a test that had to make a real target use eight hundred
 * megabytes would be testing the target.
 *
 * <p>What each test is really checking is that nobody is told twice and nobody is told too soon —
 * the two ways an alerting feature becomes something everybody mutes.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class AlertsTest {

    private static final Instant NOON = Instant.parse("2026-08-20T12:00:00Z");

    @Inject AlertEvaluator evaluator;

    private Long target;

    @BeforeEach
    void setUp() {
        AlertFixtures.deleteEveryRule();
        ConnectionFixtures.deleteAllProfiles();

        // Deliberately somewhere nothing is listening. A rule keeps its target sampled, and a
        // target that answers would be feeding the rules real readings while these tests hand
        // them made-up ones — the state under test would then depend on how much memory a
        // container happened to be using. Silence leaves every rule about a quantity exactly
        // as it was, which is the property that makes this safe, and is itself tested below.
        target = ConnectionFixtures.createProfile("payments-cache", "127.0.0.1", 1);
    }

    @Test
    void theMetricsAreAClosedListThatSaysWhichOnesAreConditions() {
        given().when()
                .get("/api/v1/alerts/metrics")
                .then()
                .statusCode(200)
                .body("name", hasItem("MEMORY_FILL_PERCENT"))
                .body("find { it.name == 'MEMORY_FILL_PERCENT' }.unit", equalTo("PERCENT"))
                .body("find { it.name == 'MEMORY_FILL_PERCENT' }.condition", equalTo(false))
                // The one metric a form must not ask a threshold for.
                .body("find { it.name == 'NO_ANSWER' }.condition", equalTo(true));
    }

    @Test
    void aNewRuleIsQuietUntilSomethingHappens() {
        create(rule("Memory", "MEMORY_USED_BYTES", 500.0, 0));

        given().when()
                .get("/api/v1/alerts")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].state", equalTo("OK"))
                .body("[0].reading", nullValue())
                // Writing a rule is how somebody opts the target into being sampled.
                .body("[0].watching", equalTo(true));
    }

    @Test
    void aRuleFiresOnceAndClearsOnce() {
        create(rule("Memory", "MEMORY_USED_BYTES", 500.0, 0));

        feed(reading(NOON, 800L), null);
        feed(reading(NOON.plusSeconds(5), 900L), reading(NOON, 800L));

        awaitEvents(1);
        given().when()
                .get("/api/v1/alerts/events")
                .then()
                .statusCode(200)
                // Twice over the threshold, one event. A condition that keeps holding is not
                // news every five seconds.
                .body("", hasSize(1))
                .body("[0].kind", equalTo("FIRED"))
                .body("[0].reading", equalTo(800.0f));

        feed(reading(NOON.plusSeconds(10), 100L), null);

        awaitEvents(2);
        given().when()
                .get("/api/v1/alerts/events")
                .then()
                .body("kind", contains("CLEARED", "FIRED"))
                .body("[0].deliveryOutcome", equalTo("NONE"));
    }

    @Test
    void aConditionHasToHoldForAsLongAsTheRuleAsks() {
        create(rule("Sustained", "MEMORY_USED_BYTES", 500.0, 60));

        feed(reading(NOON, 800L), null);

        given().when()
                .get("/api/v1/alerts")
                .then()
                // Over the threshold, and nobody has been woken up: this is the state that
                // stops a scrape spike from becoming an alert everybody mutes.
                .body("[0].state", equalTo("PENDING"));
        // This rule's events, rather than every event there is. A rule about silence written by
        // another test in this class can still be firing against a target at 127.0.0.1:1 when this
        // one asks, and an assertion about the whole table then fails for something that has
        // nothing to do with what it is checking — which is that a threshold crossed once has not
        // woken anybody yet.
        given().when()
                .get("/api/v1/alerts/events")
                .then()
                .body("findAll { it.ruleName == 'Sustained' }", hasSize(0));

        feed(reading(NOON.plusSeconds(61), 850L), null);

        given().when().get("/api/v1/alerts").then().body("[0].state", equalTo("FIRING"));
        awaitEvents(1);
    }

    @Test
    void aTargetThatSaysNothingLeavesAQuantityAloneAndFiresTheRuleAboutSilence() {
        create(rule("Memory", "MEMORY_USED_BYTES", 500.0, 0));

        feed(reading(NOON, 800L), null);
        awaitEvents(1);

        create(rule("Unreachable", "NO_ANSWER", 0.0, 0));
        onContext(() -> evaluator.onSilence(target));

        awaitEvents(2);
        given().when()
                .get("/api/v1/alerts/events")
                .then()
                // The memory rule did not clear: a target that said nothing did not say the
                // memory went down.
                .body("find { it.ruleName == 'Unreachable' }.kind", equalTo("FIRED"))
                .body("findAll { it.ruleName == 'Memory' }", hasSize(1));
    }

    @Test
    void aRuleAboutATargetThatIsNotThereIsRefused() {
        Map<String, Object> body = rule("Nowhere", "MEMORY_USED_BYTES", 1.0, 0);
        body.put("connectionId", 999_999);

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alerts?connectionId=999999")
                .then()
                .statusCode(409)
                .body("message", containsString("No such target"));
    }

    @Test
    void aRuleThatSendsSomewhereThatDoesNotExistIsRefused() {
        Map<String, Object> body = rule("Memory", "MEMORY_USED_BYTES", 1.0, 0);
        body.put("deliveryIds", java.util.List.of(999_999));

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(409)
                .body("message", containsString("does not exist"));
    }

    @Test
    void aConditionCannotBeGivenAThresholdThatWouldMeanNothing() {
        Map<String, Object> body = rule("Unreachable", "NO_ANSWER", 42.0, 0);

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(201)
                // Stored as zero rather than as the number that arrived: a displayed threshold
                // nothing compares against is a lie somebody would later "correct".
                .body("threshold", equalTo(0.0f));
    }

    @Test
    void anEditedRuleForgetsWhereItStood() {
        int id = create(rule("Memory", "MEMORY_USED_BYTES", 500.0, 0));
        feed(reading(NOON, 800L), null);
        awaitEvents(1);

        Map<String, Object> raised = rule("Memory", "MEMORY_USED_BYTES", 5000.0, 0);
        given().contentType(ContentType.JSON)
                .body(raised)
                .when()
                .put("/api/v1/alerts/" + id + "?connectionId=" + target)
                .then()
                .statusCode(200)
                // Not FIRING: the threshold moved, so the old verdict is about a rule that no
                // longer exists. Announcing a change that never happened is worse.
                .body("state", equalTo("OK"));
    }

    @Test
    void deletingARuleTakesItsHistoryWithIt() {
        int id = create(rule("Memory", "MEMORY_USED_BYTES", 500.0, 0));
        feed(reading(NOON, 800L), null);
        awaitEvents(1);

        given().when()
                .delete("/api/v1/alerts/" + id + "?connectionId=" + target)
                .then()
                .statusCode(204);

        given().when().get("/api/v1/alerts/events").then().body("", hasSize(0));
    }

    // --- Helpers -----------------------------------------------------------

    private Map<String, Object> rule(String name, String metric, double threshold, int forSeconds) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("connectionId", target);
        body.put("metric", metric);
        body.put("comparison", "ABOVE");
        body.put("threshold", threshold);
        body.put("forSeconds", forSeconds);
        body.put("enabled", true);
        return body;
    }

    private int create(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    /**
     * Hands the evaluator a reading, on a context it can work on.
     *
     * <p>Not from the test thread directly: everything the evaluator does afterwards is Hibernate
     * Reactive, which runs on a Vert.x context and nowhere else. This is the same arrangement the
     * sampler's timer gives it in production.
     */
    private void feed(MetricsSample now, MetricsSample before) {
        onContext(() -> evaluator.onReading(target, now, before));
    }

    private void onContext(Runnable work) {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Uni.createFrom()
                                    .item(
                                            () -> {
                                                work.run();
                                                return true;
                                            }));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not hand the evaluator a reading", failure);
        }
    }

    /** The event is written outside the request that caused it, so the test waits for the row. */
    private void awaitEvents(int expected) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/alerts/events")
                                        .then()
                                        .body("", hasSize(expected)));
    }

    private static MetricsSample reading(Instant at, Long usedBytes) {
        return new MetricsSample(
                at, usedBytes, usedBytes, 1000L, 3L, 5L, 100L, 8L, 2L, 42L, 900L, 0L, 0L);
    }
}
