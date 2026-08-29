package io.keydra.cluster;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.cluster.persistence.InstanceRepository;
import io.keydra.common.workload.Workload;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The roster answers "who is here", which the lease table cannot.
 *
 * <p>Two instances and one leader is the ordinary state of a rolling upgrade, and reading either
 * number alone gives the wrong picture of the same moment. These are the claims the page rests on:
 * more than one row is kept, a row that has not been heard from is not listed, and a clean stop
 * removes one rather than leaving it to age out.
 */
@QuarkusTest
class InstanceRosterTest {

    @Inject InstanceRepository repository;

    @Test
    @RunOnVertxContext
    void keepsARowPerInstance(UniAsserter asserter) {
        asserter.execute(() -> repository.forget("roster-one"));
        asserter.execute(() -> repository.forget("roster-two"));
        asserter.execute(
                () ->
                        repository.announce(
                                "roster-one",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));
        asserter.execute(
                () ->
                        repository.announce(
                                "roster-two",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));

        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows ->
                        assertThat(
                                idsIn(rows),
                                hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(2))));
    }

    /**
     * Announcing again is a beat, not a restart.
     *
     * <p>The start time has to survive it — an instance whose uptime reset every five seconds would
     * be an instance that looks like it is crash-looping when it is doing exactly what it should.
     */
    @Test
    @RunOnVertxContext
    void aSecondAnnouncementKeepsTheFirstStartTime(UniAsserter asserter) {
        // Held in a field rather than passed along, because the two reads happen in two steps of
        // the same chain and there is nowhere between them to put a local.
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> first =
                new java.util.concurrent.atomic.AtomicReference<>();

        asserter.execute(() -> repository.forget("roster-uptime"));
        asserter.execute(
                () ->
                        repository.announce(
                                "roster-uptime",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));
        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows -> first.set(rowOf(rows, "roster-uptime").startedAt));

        asserter.execute(
                () ->
                        repository.announce(
                                "roster-uptime",
                                "1.1",
                                "bbbb",
                                3,
                                7,
                                0,
                                Workload.Snapshot.NONE,
                                false));
        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows -> {
                    KeydraInstance row = rowOf(rows, "roster-uptime");
                    assertThat(row.startedAt, is(first.get()));
                    // The build does change: an instance upgraded in place is the same instance,
                    // and saying so is the whole reason a rolling upgrade is readable.
                    assertThat(row.version, is("1.1"));
                    // And so do the counters, which is what makes two readings a rate.
                    assertThat(row.published, is(3L));
                    assertThat(row.received, is(7L));
                });
    }

    /** A row nobody has written for a while is not a running instance. */
    @Test
    @RunOnVertxContext
    void doesNotListAnInstanceItHasNotHeardFrom(UniAsserter asserter) {
        asserter.execute(() -> repository.forget("roster-quiet"));
        asserter.execute(
                () ->
                        repository.announce(
                                "roster-quiet",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));

        // A window of zero seconds ago: everything is older than that, including what was just
        // written, which is the cheapest way to ask the question the cutoff exists to answer.
        asserter.assertThat(
                () -> repository.seenWithin(0).map(InstanceRosterTest::idsIn),
                ids -> assertThat(ids.contains("roster-quiet"), is(false)));
    }

    @Test
    @RunOnVertxContext
    void aCleanStopRemovesTheRow(UniAsserter asserter) {
        asserter.execute(
                () ->
                        repository.announce(
                                "roster-leaving",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));
        asserter.execute(() -> repository.forget("roster-leaving"));

        asserter.assertThat(
                () -> repository.seenWithin(600).map(InstanceRosterTest::idsIn),
                ids -> assertThat(ids.contains("roster-leaving"), is(false)));
    }

    private static List<String> idsIn(List<KeydraInstance> rows) {
        return rows.stream().map(row -> row.id).toList();
    }

    private static KeydraInstance rowOf(List<KeydraInstance> rows, String id) {
        return rows.stream()
                .filter(row -> row.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + id));
    }
}
