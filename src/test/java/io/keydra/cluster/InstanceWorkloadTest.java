package io.keydra.cluster;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.cluster.persistence.InstanceRepository;
import io.keydra.cluster.persistence.TargetListConverter;
import io.keydra.common.workload.Workload;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.vertx.RunOnVertxContext;
import io.quarkus.test.vertx.UniAsserter;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What an instance is holding, as the roster records it.
 *
 * <p>Phase 39 said who is here; this says what each of them has in its hands. The claims the page
 * rests on are that the numbers survive a round trip, that the target list comes back as a list
 * rather than as the text it was stored in, and that a beat overwrites the last answer rather than
 * accumulating — an instance that closed every socket should report none, not the count it had when
 * it was busy.
 */
@QuarkusTest
class InstanceWorkloadTest {

    @Inject InstanceRepository repository;

    @Test
    @RunOnVertxContext
    void carriesTheNumbersAndTheTargets(UniAsserter asserter) {
        asserter.execute(() -> repository.forget("workload-one"));
        asserter.execute(
                () ->
                        repository.announce(
                                "workload-one",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                new Workload.Snapshot(4, 2, 1, Set.of(7L, 9L)),
                                false));

        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows -> {
                    KeydraInstance row = rowOf(rows, "workload-one");
                    assertThat(row.sockets, is(4));
                    assertThat(row.streams, is(2));
                    assertThat(row.jobs, is(1));
                    // Sorted on the way in, so the column is stable for a list that has not
                    // changed.
                    assertThat(row.watching, contains(7L, 9L));
                });
    }

    /**
     * A beat replaces the last answer.
     *
     * <p>The counters beside these are cumulative and these are not, which is the distinction worth
     * asserting: an instance that has closed everything reports nothing, and a row that kept the
     * busiest number it ever saw would make an idle fleet look permanently loaded.
     */
    @Test
    @RunOnVertxContext
    void aLaterBeatReplacesWhatTheEarlierOneSaid(UniAsserter asserter) {
        asserter.execute(() -> repository.forget("workload-busy"));
        asserter.execute(
                () ->
                        repository.announce(
                                "workload-busy",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                new Workload.Snapshot(9, 9, 9, Set.of(1L, 2L, 3L)),
                                false));
        asserter.execute(
                () ->
                        repository.announce(
                                "workload-busy",
                                "1.0",
                                "aaaa",
                                0,
                                0,
                                0,
                                Workload.Snapshot.NONE,
                                false));

        asserter.assertThat(
                () -> repository.seenWithin(600),
                rows -> {
                    KeydraInstance row = rowOf(rows, "workload-busy");
                    assertThat(row.sockets, is(0));
                    assertThat(row.streams, is(0));
                    assertThat(row.jobs, is(0));
                    assertThat(row.watching, is(empty()));
                });
    }

    /**
     * The column is a report rather than a record.
     *
     * <p>Anything unreadable in it comes back empty instead of throwing, because a roster that
     * failed to load over one odd row would be the page for diagnosing a fleet taken down by the
     * fleet.
     */
    @Test
    void unreadableIdsAreSkippedRatherThanThrown() {
        TargetListConverter converter = new TargetListConverter();
        assertThat(converter.convertToEntityAttribute("1,not-an-id,3"), contains(1L, 3L));
        assertThat(converter.convertToEntityAttribute(""), is(empty()));
        assertThat(converter.convertToEntityAttribute(null), is(empty()));
    }

    /** Sorted and joined, so an unchanged list writes the same string every beat. */
    @Test
    void writesTheSameStringForTheSameList() {
        assertThat(TargetListConverter.join(Set.of(3L, 1L, 2L)), is("1,2,3"));
        assertThat(TargetListConverter.join(Set.of()), is(""));
        assertThat(TargetListConverter.join(null), is(""));
    }

    private static KeydraInstance rowOf(List<KeydraInstance> rows, String id) {
        return rows.stream()
                .filter(row -> row.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + id));
    }
}
