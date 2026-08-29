package io.keydra.cluster;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import io.keydra.AbstractTestBase;
import io.keydra.cluster.entity.InstanceNoticeState;
import io.keydra.cluster.persistence.NoticeStateRepository;
import io.keydra.cluster.service.InstanceId;
import io.keydra.cluster.service.InstanceWatch;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Keydra noticing that something has happened to Keydra.
 *
 * <p>Asserted on the state rather than on a message arriving, deliberately. Where the message goes
 * is phase 55's question and is answered by its own list of destinations; what this phase decides
 * is <em>whether</em> there is one — which silence is a death and which is somebody stopping a pod
 * on purpose, and which instance gets to be the one that speaks. That decision is a row, and a row
 * is a thing a test can read.
 */
@QuarkusTest
class InstanceWatchTest extends AbstractTestBase {

    private static final String DEAD = "keydra-that-died";

    @Inject InstanceWatch watch;

    @Inject NoticeStateRepository states;

    @BeforeEach
    void setUp() {
        ClusterFixtures.forgetInstance(DEAD);
        ClusterFixtures.clearWhatWasSaid();
    }

    @AfterEach
    void tidy() {
        ClusterFixtures.forgetInstance(DEAD);
        ClusterFixtures.clearWhatWasSaid();
        ClusterFixtures.resumeThisInstance();
        ClusterFixtures.takeTheChoresBack();
    }

    @Test
    void anInstanceThatStoppedWithoutShuttingDownIsAnnounced() {
        // Two leases of silence is a death rather than a slow beat, and the lease is three
        // seconds here.
        ClusterFixtures.pretendInstance(DEAD, 60, false);

        look();

        assertThat(ClusterFixtures.absenceAnnounced(DEAD), is(true));
    }

    @Test
    void andIsAnnouncedOnceHoweverManyInstancesNotice() {
        ClusterFixtures.pretendInstance(DEAD, 60, false);
        look();

        // The second look is every other instance in the fleet: the update names the state it
        // expects to find, so exactly one of them changes a row and the rest are told no. This is
        // the whole reason this works without anybody being in charge.
        look();

        assertThat(ClusterFixtures.absenceAnnounced(DEAD), is(true));
    }

    @Test
    void anInstanceSomebodyDrainedAndThenStoppedIsNot() {
        // A departure with a step in front of it. Somebody took it out of service and then
        // stopped it, which is the opposite of the thing worth announcing.
        ClusterFixtures.pretendInstance(DEAD, 60, true);

        look();

        assertThat(ClusterFixtures.absenceAnnounced(DEAD), is(false));
    }

    @Test
    void anInstanceThatIsStillBeatingIsNot() {
        ClusterFixtures.pretendInstance(DEAD, 0, false);

        look();

        assertThat(ClusterFixtures.absenceAnnounced(DEAD), is(false));
    }

    @Test
    void anInstanceThatLeftCleanlyIsNotAnnouncedAtAll() {
        // It removed its own row on the way out, which is the whole distinction: a row that
        // vanishes is a departure and a row that ages is a death. There is nothing here to find,
        // and the test says so by leaving nothing to find.
        ClusterFixtures.pretendInstance(DEAD, 60, false);
        ClusterFixtures.forgetInstance(DEAD);

        look();

        assertThat(ClusterFixtures.absenceAnnounced(DEAD), is(false));
    }

    @Test
    void theInstanceAnsweringIsNeverAnnouncedAsGone() {
        look();

        // It has just written its own beat, which is what makes this the regression test for a
        // window written the wrong way round.
        assertThat(ClusterFixtures.absenceAnnounced(InstanceId.get()), is(false));
    }

    @Test
    void aFleetWithNobodyDoingTheChoresAnnouncesItselfAndThenStops() {
        // How it happens for real: every instance drained, so nobody asks for the lease and the
        // last one lapses. An expired lease on its own would be claimed by the next beat.
        ClusterFixtures.drainThisInstance();
        ClusterFixtures.letTheChoresLapse(600);

        look();
        assertThat(ClusterFixtures.choresAnnounced(), is(true));

        // And a second look says nothing more, for the reason a second instance would not.
        assertThat(begin(), is(false));

        ClusterFixtures.resumeThisInstance();
        ClusterFixtures.takeTheChoresBack();

        look();
        // The line that lets whoever read the first message stop worrying. Phase 55's argument for
        // both edges, and the reason this one is a condition rather than an event.
        assertThat(ClusterFixtures.choresAnnounced(), is(false));
    }

    @Test
    void aHandoverIsNotAFleetThatHasStopped() {
        // The lease lapsed a moment ago, which is what every handover looks like. Ten leases is
        // the line, and a page or a channel that fired on this one would fire on every restart.
        ClusterFixtures.drainThisInstance();
        ClusterFixtures.letTheChoresLapse(1);

        look();

        assertThat(ClusterFixtures.choresAnnounced(), is(false));
    }

    @Test
    void aLeaseThatHasNeverLapsedIsNotNews() {
        // No row, or a row somebody holds — the two states of an installation where nothing has
        // gone wrong. Neither has a date on it and neither is news, which is what stops a fresh
        // instance announcing a fleet failure on the way up.
        ClusterFixtures.takeTheChoresBack();

        look();

        assertThat(ClusterFixtures.choresAnnounced(), is(false));
    }

    private void look() {
        onContext(watch::inspect);
    }

    /** Whether this caller would be the one to speak, which after a look it must not be. */
    private boolean begin() {
        return Boolean.TRUE.equals(onContext(() -> states.begin(InstanceNoticeState.CHORES)));
    }

    /**
     * Calls the method on a Vert.x context rather than assembling it on the JUnit thread.
     *
     * <p>The session interceptor wraps the call, not the subscription, so a {@code Uni} built
     * outside a context is one whose session was decided outside a context — which fails with "no
     * current Vertx context" at the point it is built rather than at the point it is used.
     */
    private static <T> T onContext(java.util.function.Supplier<io.smallrye.mutiny.Uni<T>> work) {
        try {
            return VertxContextSupport.subscribeAndAwait(work::get);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not look at the fleet", failure);
        }
    }
}
