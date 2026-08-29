package io.keydra.cluster;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.keydra.AbstractTestBase;
import io.keydra.cluster.persistence.InstanceRepository;
import io.keydra.cluster.service.InstanceRegistry;
import io.keydra.cluster.service.Leadership;
import io.keydra.common.workload.Workload;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Taking an instance out of service without stopping it.
 *
 * <p>Two halves that are tested differently because they fail differently. The instruction is a
 * column somebody else writes, and what could go wrong there is arithmetic on a row: an
 * announcement that undoes it, or a process that inherits one meant for the process before it. What
 * follows from it is behaviour — readiness going down, the chores moving — and the only honest way
 * to check that is to drain this instance and watch.
 */
@QuarkusTest
class InstanceDrainTest extends AbstractTestBase {

    /** The check the readiness answer is about; the others on that endpoint are not this test's. */
    private static final String CHECK = "checks.find { it.name == 'Accepting traffic' }";

    @Inject InstanceRepository repository;
    @Inject InstanceRegistry instances;
    @Inject Leadership leadership;

    /**
     * The point of the column being absent from the update list.
     *
     * <p>Every other column on the row is the instance overwriting what was there with what it
     * currently believes. Doing that here would undo the instruction on the next beat, which is a
     * second or so after it was given.
     */
    @Test
    @RunOnVertxContext
    void aLaterBeatDoesNotUndoTheInstruction(UniAsserter asserter) {
        asserter.execute(() -> repository.forget("drain-kept"));
        asserter.execute(() -> beat("drain-kept", true));
        asserter.execute(() -> repository.setDraining("drain-kept", true));

        asserter.assertThat(
                () -> beat("drain-kept", false), draining -> assertThat(draining, is(true)));
        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows -> assertThat(rowOf(rows, "drain-kept"), is(true)));
    }

    /**
     * A drain applies to a process, not to a name.
     *
     * <p>An instance killed while draining leaves its row behind, and a process that comes back
     * under the same configured name would otherwise start up refusing to serve for a reason nobody
     * typed and nowhere to see.
     */
    @Test
    @RunOnVertxContext
    void theFirstAnnouncementOfAProcessClearsIt(UniAsserter asserter) {
        asserter.execute(() -> repository.forget("drain-restarted"));
        asserter.execute(() -> beat("drain-restarted", true));
        asserter.execute(() -> repository.setDraining("drain-restarted", true));

        asserter.assertThat(
                () -> beat("drain-restarted", true), draining -> assertThat(draining, is(false)));
        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows -> assertThat(rowOf(rows, "drain-restarted"), is(false)));
    }

    /** A name nobody is running under is a mistake worth reporting rather than a quiet success. */
    @Test
    @RunOnVertxContext
    void thereIsNothingToDrainUnderANameNobodyIsUsing(UniAsserter asserter) {
        asserter.assertThat(
                () -> repository.setDraining("nobody-is-called-this", true),
                found -> assertThat(found, is(false)));
    }

    @Test
    void theEndpointSaysSoTooWhenNobodyIsRunningUnderThatName() {
        given().when().post("/api/v1/instances/nobody-is-called-this/drain").then().statusCode(404);
        given().when()
                .delete("/api/v1/instances/nobody-is-called-this/drain")
                .then()
                .statusCode(404);
    }

    /**
     * The whole chain, on the instance running the test.
     *
     * <p>The row is written, the beat reads it back, readiness goes down and the lease is handed
     * over — and none of the four is worth much without the other three. Asserted about the named
     * check rather than the endpoint's overall answer, so this stays a test about draining rather
     * than about whether everything else Keydra depends on was reachable at that moment.
     */
    @Test
    void drainingThisInstanceTakesItOutOfServiceAndGivesTheChoresBack() {
        ClusterFixtures.takeTheChoresBack();
        String self = instances.self();
        ready(equalTo("UP"));

        given().when().post("/api/v1/instances/" + self + "/drain").then().statusCode(204);
        try {
            // Immediately, because this is the row rather than what follows from it.
            given().when()
                    .get("/api/v1/instances/roster")
                    .then()
                    .body("find { it.self == true }.draining", equalTo(true));

            Awaitility.await()
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> ready(equalTo("DOWN")));
            given().when()
                    .get("/q/health/ready")
                    .then()
                    .body("status", equalTo("DOWN"))
                    .body(CHECK + ".data.state", equalTo("draining"));

            // Handed back rather than left to expire, which is the difference between the chores
            // stopping for a beat and stopping for the rest of a lease.
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> !leadership.isLeader());
        } finally {
            given().when().delete("/api/v1/instances/" + self + "/drain").then().statusCode(204);
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> ready(equalTo("UP")));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(leadership::isLeader);
    }

    private static void ready(org.hamcrest.Matcher<String> status) {
        given().when().get("/q/health/ready").then().body(CHECK + ".status", status);
    }

    private io.smallrye.mutiny.Uni<Boolean> beat(String id, boolean first) {
        return repository.announce(id, "1.0", "aaaa", 0, 0, 0, Workload.Snapshot.NONE, first);
    }

    private static boolean rowOf(
            java.util.List<io.keydra.cluster.entity.KeydraInstance> rows, String id) {
        return rows.stream()
                .filter(row -> row.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + id))
                .draining;
    }
}
