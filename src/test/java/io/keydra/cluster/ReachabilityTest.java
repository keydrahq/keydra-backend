package io.keydra.cluster;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.backup.service.DestinationReachability;
import io.keydra.cluster.persistence.ReachabilityRepository;
import io.keydra.cluster.service.Reachability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whether the things outside Keydra answer, found out somewhere other than a page load.
 *
 * <p>Phase 40 refused to probe when the page is drawn and was right — ten people watching would be
 * ten times the load of one, aimed at somebody else's service. What is worth pinning here is that
 * the refusal survives: the page reads a row, the asking happens once, and the button that asks
 * again cannot be held down.
 */
@QuarkusTest
class ReachabilityTest {

    @Inject Reachability reachability;

    @Inject ReachabilityRepository repository;

    @BeforeEach
    void nothingOnRecord() {
        deleteEveryDestination();
        await(() -> repository.deleteAll());
        // And what was said to have happened. The history outlives the thing it is about on
        // purpose — which is the point of it and the reason clearing the answers is not enough.
        await(() -> repository.forgetHistoryBefore(java.time.Instant.now().plusSeconds(3600)));
    }

    /**
     * The page says nothing it has not found out.
     *
     * <p>The absence of a reading is the fact, and it is what the row carries: no {@code reached}
     * at all rather than a verdict invented from not having asked. What the interface makes of that
     * — "not checked yet" — is a sentence, and sentences are written where the language is known.
     */
    @Test
    void aGroupNothingHasAskedSaysSoRatherThanClaimingHealth() {
        createDestination("never-asked", "reach-never");

        given().when()
                .get("/api/v1/instances")
                .then()
                .statusCode(200)
                .body("dependencies.find { it.id == 'backup-destinations' }.reached", nullValue())
                .body("dependencies.find { it.id == 'backup-destinations' }.count", equalTo(1))
                // And it is not made red by not having been asked. It has not been asked.
                .body("dependencies.find { it.id == 'backup-destinations' }.reachable", is(true));
    }

    @Test
    void aDestinationThatWorksIsRecordedAsAnswering() {
        createDestination("works", "reach-works");

        given().when().post("/api/v1/instances/reachability").then().statusCode(200);

        given().when()
                .get("/api/v1/instances")
                .then()
                .body(
                        "dependencies.find { it.id == 'backup-destinations' }.reached.asked",
                        equalTo(1))
                .body(
                        "dependencies.find { it.id == 'backup-destinations' }.reached.answering",
                        equalTo(1))
                .body(
                        "dependencies.find { it.id == 'backup-destinations' }.reached.at",
                        notNullValue())
                .body("dependencies.find { it.id == 'backup-destinations' }.reachable", is(true));
    }

    /**
     * The reading the whole phase is for.
     *
     * <p>A destination whose credentials stopped working is discovered at three in the morning
     * three weeks later, unless something asks. This is the something.
     */
    @Test
    void aDestinationThatDoesNotAnswerTurnsTheGroupRed() {
        // A local path under a file that is not a directory: the write fails the way a bucket
        // whose credentials expired fails, which is with a message rather than a hang.
        createDestination("broken", "/dev/null/keydra-cannot-write-here");

        given().when().post("/api/v1/instances/reachability").then().statusCode(200);

        given().when()
                .get("/api/v1/instances")
                .then()
                .body(
                        "dependencies.find { it.id == 'backup-destinations' }.reached.asked",
                        equalTo(1))
                .body(
                        "dependencies.find { it.id == 'backup-destinations' }.reached.answering",
                        equalTo(0))
                .body("dependencies.find { it.id == 'backup-destinations' }.reachable", is(false));
    }

    /** Off because somebody turned it off, so asking would be a request on behalf of that. */
    @Test
    void somethingSwitchedOffIsNotAsked() {
        createDestination("switched-off", "reach-off", false);

        given().when().post("/api/v1/instances/reachability").then().statusCode(200);

        given().when()
                .get("/api/v1/instances")
                .then()
                .body("dependencies.find { it.id == 'backup-destinations' }.reached", nullValue());
    }

    /**
     * A button that can be held down is a way to make Keydra hammer somebody else's service from a
     * page that only needs {@code instance:read}.
     */
    @Test
    void theButtonCannotBeHeldDown() {
        createDestination("works", "reach-again");

        given().when().post("/api/v1/instances/reachability").then().statusCode(200);
        given().when().post("/api/v1/instances/reachability").then().statusCode(429);
    }

    /** No foreign key, so this is what stops an answer outliving what it was about. */
    @Test
    @RunOnVertxContext
    void anAnswerAboutSomethingDeletedIsForgotten(UniAsserter asserter) {
        int id = createDestination("temporary", "reach-gone");
        given().when().post("/api/v1/instances/reachability").then().statusCode(200);
        given().when().delete("/api/v1/backup-destinations/" + id).then().statusCode(204);

        asserter.execute(() -> reachability.ask());
        asserter.assertThat(
                () -> repository.forKind(DestinationReachability.KIND), found -> found.isEmpty());
    }

    /**
     * A first answer is where the line starts, not an alarm.
     *
     * <p>Phase 49 refuses to announce a first sighting, because announcing one would make adding a
     * destination an alarm. The history keeps it anyway: a timeline whose first entry is a failure,
     * with nothing before it, reads as though something broke — where what happened is that
     * somebody added it that morning. Two questions, two answers.
     */
    @Test
    void aFirstAnswerStartsTheHistory() {
        createDestination("nightly-s3", "reach-first");

        given().when().post("/api/v1/instances/reachability").then().statusCode(200);

        given().when()
                .get("/api/v1/instances/reachability/history")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].name", equalTo("nightly-s3"))
                .body("[0].ok", is(true));
    }

    /** The thing phase 49 was right to refuse: a row per answer is a table that says nothing. */
    @Test
    @RunOnVertxContext
    void askingAgainWithNothingChangedKeepsNothing(UniAsserter asserter) {
        createDestination("steady", "reach-steady");

        asserter.execute(() -> reachability.ask());
        asserter.execute(() -> reachability.ask());
        asserter.execute(() -> reachability.ask());

        asserter.assertThat(
                () -> repository.history(null, null, 50), rows -> assertThat(rows, hasSize(1)));
    }

    /**
     * Stopping and starting again are two rows, and the second is the one that closes the first.
     */
    @Test
    void stoppingAndStartingAgainAreTwoMoreRows() {
        int id = createDestination("flaky", "reach-flaky");
        given().when().post("/api/v1/instances/reachability").then().statusCode(200);

        update(id, "flaky", "/dev/null/keydra-cannot-write-here");
        askAgain();
        update(id, "flaky", "reach-flaky");
        askAgain();

        given().when()
                .get("/api/v1/instances/reachability/history")
                .then()
                .statusCode(200)
                // Newest first, which is how a timeline is read.
                .body("ok", contains(true, false, true))
                .body("[1].detail", notNullValue());
    }

    /**
     * A destination somebody deleted last week still stopped answering on Tuesday.
     *
     * <p>The current answer is forgotten when the thing is — there is no foreign key, and phase 49
     * forgets rather than cascades. The history is the other half of that decision: it keeps the
     * name it had at the time, so what happened stays readable after the row it was about is gone.
     */
    @Test
    void theHistoryOutlivesWhatItWasAbout() {
        int id = createDestination("since-deleted", "reach-deleted");
        given().when().post("/api/v1/instances/reachability").then().statusCode(200);

        given().when().delete("/api/v1/backup-destinations/" + id).then().statusCode(204);
        askAgain();

        given().when()
                .get("/api/v1/instances/reachability/history")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].name", equalTo("since-deleted"));
    }

    /** A history nobody prunes grows for as long as the application runs. */
    @Test
    @RunOnVertxContext
    void whatHappenedLongEnoughAgoIsForgotten(UniAsserter asserter) {
        createDestination("old-news", "reach-old");
        asserter.execute(() -> reachability.ask());

        asserter.assertThat(
                () -> repository.forgetHistoryBefore(java.time.Instant.now().plusSeconds(60)),
                removed -> assertThat(removed, is(1)));
        asserter.assertThat(
                () -> repository.history(null, null, 50), rows -> assertThat(rows, empty()));
    }

    // --- Helpers -----------------------------------------------------------

    /** The walk without the button's guard, for a test that asks more than once in a second. */
    private void askAgain() {
        await(() -> reachability.ask());
    }

    private static void update(int id, String name, String path) {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", name, "kind", "LOCAL", "path", path, "enabled", true))
                .when()
                .put("/api/v1/backup-destinations/" + id)
                .then()
                .statusCode(200);
    }

    private static int createDestination(String name, String path) {
        return createDestination(name, path, true);
    }

    private static int createDestination(String name, String path, boolean enabled) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("name", name, "kind", "LOCAL", "path", path, "enabled", enabled))
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private static void deleteEveryDestination() {
        List<Integer> ids =
                given().when()
                        .get("/api/v1/backup-destinations")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("id");
        for (Integer id : ids) {
            given().when().delete("/api/v1/backup-destinations/" + id).then().statusCode(204);
        }
    }

    private static <T> void await(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) {
        try {
            io.quarkus.vertx.VertxContextSupport.subscribeAndAwait(work);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
