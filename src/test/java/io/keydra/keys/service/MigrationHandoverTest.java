package io.keydra.keys.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.keydra.cluster.ClusterFixtures;
import io.keydra.cluster.persistence.InstanceRepository;
import io.keydra.cluster.service.Leadership;
import io.keydra.common.workload.Workload;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.keys.dto.MigrationJob;
import io.keydra.keys.entity.MigrationRun;
import io.keydra.keys.persistence.MigrationRepository;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Migrations do not stop because the instance running one did.
 *
 * <p>The claim this rests on is easy to state and easy to get wrong in either direction: work left
 * by an instance that is gone must be carried on without anybody asking, and work being done by an
 * instance that is still here must be left alone. A sweep that only did the first would be a sweep
 * that occasionally has two Keydras walking the same keyspace.
 *
 * <p>Both ends are real. The row is written the way a killed process leaves one — RUNNING, under a
 * name, with what it was asked to do — and the migration the sweep starts actually moves keys
 * between two containers, because a handover that claims a row and walks nothing would pass a test
 * that only looked at the row.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class MigrationHandoverTest {

    @Inject MigrationHandover handover;
    @Inject MigrationRepository repository;
    @Inject InstanceRepository instances;
    @Inject Leadership leadership;

    private int sourceId;
    private int targetId;

    @BeforeEach
    void setUp() {
        // The history first: a row left running by the test before this one is a row this test's
        // sweep would pick up, and a count that was meant to be one is then two.
        ConnectionFixtures.deleteAllMigrations();
        ConnectionFixtures.deleteAllProfiles();
        sourceId = createConnection("handover-source");
        targetId = createConnection("handover-target");
        RedisTargetsResource.flushRedis();
        RedisTargetsResource.execRedis("SET", "handover:1", "one");
        RedisTargetsResource.execRedis("SET", "handover:2", "two");
        RedisTargetsResource.execRedis("SET", "handover:3", "three");
    }

    @Test
    void aMigrationLeftByAnInstanceThatWentAwayIsCarriedOn() {
        // Exactly what a killed process leaves: a row that says it is still going, under a name
        // that is not in the roster because the instance it belonged to never came back.
        await(() -> repository.start(stranded("left-behind", "an-instance-that-is-gone")));

        await(handover::sweep);

        // Taken over rather than written off, and it is a migration rather than a claim: the keys
        // are on the other end. Asserted together at the end because a job that is still walking
        // reports itself from memory, and the point here is that both readings agree.
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/migrations")
                                        .then()
                                        .body(
                                                "find { it.id == 'left-behind' }.state",
                                                equalTo("DONE"))
                                        .body(
                                                "find { it.id == 'left-behind' }.migrated",
                                                equalTo(3))
                                        // A job whose counters went back to the beginning needs the
                                        // word that explains why in the same row.
                                        .body(
                                                "find { it.id == 'left-behind' }.resumed",
                                                greaterThanOrEqualTo(1)));
    }

    /**
     * The case a restart used to answer by writing the work off.
     *
     * <p>A row under this instance's own name that this instance is not walking. After a crash that
     * is every row the process had, and there is nothing to claim from anybody — it is already
     * ours. What used to happen was that startup marked it interrupted and it stayed that way until
     * somebody noticed.
     */
    @Test
    void aMigrationThisInstanceLeftBehindIsPickedUpAgainByItself() {
        await(() -> repository.start(stranded("left-by-me", leadership.instanceId())));

        await(handover::sweep);

        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get("/api/v1/migrations")
                                        .then()
                                        .body(
                                                "find { it.id == 'left-by-me' }.state",
                                                equalTo("DONE"))
                                        .body("find { it.id == 'left-by-me' }.migrated", equalTo(3))
                                        .body(
                                                "find { it.id == 'left-by-me' }.resumed",
                                                greaterThanOrEqualTo(1)));
    }

    /**
     * An instance on its way out takes nothing new.
     *
     * <p>The row is abandoned and this instance holds the chores, so ordinarily it would be picked
     * up here and now. Somebody has asked this instance to stop, and starting a walk of somebody
     * else's keyspace on the way out would mean the same work being handed over twice — which is
     * the one behaviour that would make draining an instance worse than simply stopping it.
     *
     * <p>Asserted while still draining rather than after resuming: the sweep has a timer of its
     * own, and a row left alone is only left alone for as long as the reason lasts.
     */
    @Test
    void aDrainingInstanceLeavesAbandonedWorkForSomebodyElse() {
        // Drained before the row exists, not after. The sweep also runs on a timer of its own —
        // every few seconds under the test profile — so a row created first is a row a background
        // sweep can claim before the drain lands, which is a race this test lost about one full
        // run in three. From the moment draining is on, no sweep touches anything.
        ClusterFixtures.drainThisInstance();
        try {
            await(
                    () ->
                            repository.start(
                                    stranded("left-for-someone-else", "an-instance-that-is-gone")));
            await(handover::sweep);

            given().when()
                    .get("/api/v1/migrations")
                    .then()
                    .body("find { it.id == 'left-for-someone-else' }.state", equalTo("RUNNING"))
                    .body("find { it.id == 'left-for-someone-else' }.resumed", equalTo(0));
        } finally {
            ClusterFixtures.resumeThisInstance();
        }
    }

    @Test
    void aMigrationBeingWalkedSomewhereElseIsLeftAlone() {
        // An instance that is announcing itself, with a fresh claim on its own work. Nothing about
        // this row is abandoned, and the sweep taking it would be one Keydra stopping another one
        // in the middle of its keyspace.
        await(
                () ->
                        instances.announce(
                                "still-here",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));
        await(() -> repository.start(stranded("running-elsewhere", "still-here")));

        await(handover::sweep);

        given().when()
                .get("/api/v1/migrations")
                .then()
                .body("find { it.id == 'running-elsewhere' }.state", equalTo("RUNNING"))
                .body("find { it.id == 'running-elsewhere' }.resumed", equalTo(0));
    }

    /**
     * A row from before migrations recorded what they had been asked to do.
     *
     * <p>It cannot be carried on — there is nothing to carry on from — and the honest ending is
     * interrupted rather than failed: nothing refused anything, the process went away and the
     * record was not complete enough to work from. What must not happen is the row staying at
     * RUNNING for ever, which is what it did before any of this existed.
     */
    @Test
    void aRowThatNeverSaidWhatItWasDoingIsEndedRatherThanLeftRunning() {
        MigrationRun run = stranded("no-request-recorded", "an-instance-that-is-gone");
        run.request = null;
        await(() -> repository.start(run));

        await(handover::sweep);

        given().when()
                .get("/api/v1/migrations")
                .then()
                .body("find { it.id == 'no-request-recorded' }.state", equalTo("INTERRUPTED"));
    }

    /**
     * Two instances deciding at the same moment, which the claim is the whole answer to.
     *
     * <p>Swept twice in a row with nothing in between. The second pass must find nothing to take:
     * the row now names this instance and this instance is walking it, so a second claim would be
     * the same job started twice against the same target.
     */
    @Test
    void aSecondSweepDoesNotTakeAJobTheFirstOneStarted() {
        await(() -> repository.start(stranded("claimed-once", "an-instance-that-is-gone")));

        Integer first = await(handover::sweep);
        Integer second = await(handover::sweep);

        org.hamcrest.MatcherAssert.assertThat(first, equalTo(1));
        org.hamcrest.MatcherAssert.assertThat(second, equalTo(0));
    }

    /** A row as a killed instance leaves it: running, named, and with its instructions on it. */
    private MigrationRun stranded(String id, String instanceId) {
        MigrationRun run = new MigrationRun();
        run.id = id;
        run.sourceConnectionId = (long) sourceId;
        run.targetConnectionId = (long) targetId;
        run.matchPattern = "handover:*";
        run.state = MigrationJob.State.RUNNING;
        run.instanceId = instanceId;
        run.claimedAt = Instant.now();
        run.startedBy = "someone";
        run.request =
                "{\"targetConnectionId\":"
                        + targetId
                        + ",\"match\":\"handover:*\",\"replace\":true,"
                        + "\"deleteFromSource\":false}";
        return run;
    }

    private int createConnection(String name) {
        return given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                name,
                                "host",
                                ConfigProvider.getConfig()
                                        .getValue(RedisTargetsResource.REDIS_HOST, String.class),
                                "port",
                                ConfigProvider.getConfig()
                                        .getValue(RedisTargetsResource.REDIS_PORT, Integer.class),
                                "tls",
                                false,
                                "database",
                                name.endsWith("target") ? 1 : 0,
                                "type",
                                "STANDALONE"))
                .when()
                .post("/api/v1/connections")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private static <T> T await(Supplier<Uni<T>> work) {
        try {
            return VertxContextSupport.subscribeAndAwait(work);
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }
}
