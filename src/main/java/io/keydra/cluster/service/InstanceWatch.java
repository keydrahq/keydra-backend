package io.keydra.cluster.service;

import io.keydra.alerts.service.InstanceNotices;
import io.keydra.cluster.entity.InstanceNoticeState;
import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.cluster.persistence.InstanceRepository;
import io.keydra.cluster.persistence.LeaseRepository;
import io.keydra.cluster.persistence.NoticeStateRepository;
import io.keydra.common.vertx.OwnContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Keydra noticing that something has happened to Keydra.
 *
 * <p>Two things, and they are not the same shape. An instance that stopped beating is an event: it
 * happened once, and what replaces it is a different instance with a different name, so there is
 * nothing to wait for that would close it. Nobody doing the chores is a condition: it begins when
 * the last lease lapses and ends when somebody claims one, and both edges are worth a message — the
 * second is the line that lets whoever read the first stop worrying.
 *
 * <p><b>Not a chore.</b> Everything in Keydra that must happen once asks {@link Leadership} first.
 * This does not, and must not: what it watches for includes nobody being in charge, so a check only
 * the leader ran would be a smoke alarm wired to the circuit it is watching. Every instance looks,
 * and the database decides who speaks — each announcement is taken with an update that names the
 * state it expects to find, so two instances seeing the same thing send one message between them.
 *
 * <p>On a timer of its own rather than on the lease's beat. The beat is what keeps an instance in
 * the fleet and it is deliberately arithmetic and one write; this is three queries, and a beat that
 * grew them would be a beat that can be slow for a reason that has nothing to do with the lease.
 */
@ApplicationScoped
public class InstanceWatch {

    private static final Logger LOG = Logger.getLogger(InstanceWatch.class);

    /**
     * How many lease intervals of silence make an instance dead rather than slow.
     *
     * <p>The same number {@code InstanceRegistry} uses to decide who is on the roster, and it has
     * to be: an instance the page has already stopped drawing and an instance nobody has been told
     * about would be two different definitions of gone.
     */
    private static final int ABSENT_AFTER_LEASES = 2;

    /**
     * And how many of nobody holding the chores is a fleet that has stopped working.
     *
     * <p>A handover is bounded — a lease lapses and the next beat of whoever is there claims it, so
     * a lease and a third at worst. Ten is comfortably past that and still short enough to matter:
     * on the default fifteen seconds it is two and a half minutes of a Keydra doing none of the
     * work it was left running to do.
     */
    private static final int CHORELESS_AFTER_LEASES = 10;

    /**
     * The same number, for the page.
     *
     * <p>Asked here rather than written twice: a page saying one thing and a message saying another
     * about one fact is how somebody learns to believe neither.
     */
    public int choresStoppedAfterSeconds() {
        return leaseSeconds * CHORELESS_AFTER_LEASES;
    }

    /** What the message calls one of these, in the same words the page does. */
    private static final String INSTANCE = "Keydra instance";

    private static final String FLEET = "Keydra";

    private final Vertx vertx;
    private final InstanceRepository instances;
    private final LeaseRepository leases;
    private final NoticeStateRepository states;
    private final InstanceNotices notices;
    private final int leaseSeconds;

    private long timerId = -1;

    @Inject
    InstanceWatch(
            Vertx vertx,
            InstanceRepository instances,
            LeaseRepository leases,
            NoticeStateRepository states,
            InstanceNotices notices,
            @ConfigProperty(name = "keydra.cluster.lease-seconds") int leaseSeconds) {
        this.vertx = vertx;
        this.instances = instances;
        this.leases = leases;
        this.states = states;
        this.notices = notices;
        this.leaseSeconds = Math.max(2, leaseSeconds);
    }

    void onStart(@Observes StartupEvent ignored) {
        // Once per lease: often enough that a fleet which has stopped is noticed in the window
        // this promises, and rare enough that a fleet of ten is ten queries a lease rather than
        // ten a beat.
        timerId = vertx.setPeriodic(leaseSeconds * 1000L, id -> look());
        LOG.debugf("Watching the fleet every %d seconds", leaseSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    private void look() {
        OwnContext.run(
                vertx,
                this::inspect,
                failure -> LOG.debug("Could not look at how the fleet is doing", failure));
    }

    /** Exposed so a test can ask rather than wait for a timer. */
    public Uni<Void> inspect() {
        return announceTheSilent()
                .flatMap(ignored -> announceTheReturned())
                .flatMap(ignored -> announceTheChores());
    }

    /**
     * An instance that stopped without saying so.
     *
     * <p>Every rolling upgrade takes instances away on purpose, and a message per pod per deploy is
     * how a channel becomes one nobody reads. What separates the two is already in the roster: an
     * instance that stops cleanly removes its own row, so a row that vanishes is a departure and a
     * row that ages is a death. One that was draining is a departure too, with a step in front of
     * it, and the query leaves those out.
     */
    private Uni<Void> announceTheSilent() {
        return instances
                .absentUnannounced(leaseSeconds * ABSENT_AFTER_LEASES)
                .flatMap(
                        absent ->
                                each(
                                        absent,
                                        one ->
                                                instances
                                                        .claimAbsence(one.id, true)
                                                        .flatMap(
                                                                mine ->
                                                                        Boolean.TRUE.equals(mine)
                                                                                ? notices.changed(
                                                                                        INSTANCE,
                                                                                        one.id,
                                                                                        false,
                                                                                        stoppedAt(
                                                                                                one))
                                                                                : Uni.createFrom()
                                                                                        .voidItem())));
    }

    /**
     * The same name beating again, which only happens where somebody configured it.
     *
     * <p>A name Keydra makes up carries something random after it, so what replaces a dead instance
     * is a different instance with a different row and this finds nothing. Where the id was
     * configured it is the same row coming back, and saying so is the truth for that deployment
     * rather than a rule written for the other one.
     */
    private Uni<Void> announceTheReturned() {
        return instances
                .returnedAfterAbsence(leaseSeconds * ABSENT_AFTER_LEASES)
                .flatMap(
                        back ->
                                each(
                                        back,
                                        one ->
                                                instances
                                                        .claimAbsence(one.id, false)
                                                        .flatMap(
                                                                mine ->
                                                                        Boolean.TRUE.equals(mine)
                                                                                ? notices.changed(
                                                                                        INSTANCE,
                                                                                        one.id,
                                                                                        true, null)
                                                                                : Uni.createFrom()
                                                                                        .voidItem())));
    }

    /**
     * Whether anybody is doing the work that must happen once.
     *
     * <p>A lease that has never existed is not news: a fresh installation has none until its first
     * beat, and an instance that has just started claims one in a third of a lease. What is news is
     * a lease that lapsed and that nobody has taken since — which is what an entirely drained fleet
     * looks like, and what a fleet with nothing left running in it looks like too.
     */
    private Uni<Void> announceTheChores() {
        return leases.lapsedAt(Leadership.CHORES)
                .flatMap(
                        lapsedAt -> {
                            boolean stopped =
                                    lapsedAt != null
                                            && lapsedAt.isBefore(
                                                    Instant.now()
                                                            .minusSeconds(
                                                                    (long) leaseSeconds
                                                                            * CHORELESS_AFTER_LEASES));
                            return stopped ? choresStopped(lapsedAt) : choresRunning();
                        });
    }

    private Uni<Void> choresStopped(Instant lapsedAt) {
        return states.begin(InstanceNoticeState.CHORES)
                .flatMap(
                        mine ->
                                Boolean.TRUE.equals(mine)
                                        ? notices.changed(
                                                FLEET,
                                                "the scheduled work",
                                                false,
                                                "No instance has done the chores since "
                                                        + lapsedAt
                                                        + " — schedules, alert rules and sweeps are"
                                                        + " not running")
                                        : Uni.createFrom().voidItem());
    }

    private Uni<Void> choresRunning() {
        return states.end(InstanceNoticeState.CHORES)
                .flatMap(
                        mine ->
                                Boolean.TRUE.equals(mine)
                                        ? notices.changed(FLEET, "the scheduled work", true, null)
                                        : Uni.createFrom().voidItem());
    }

    private static String stoppedAt(KeydraInstance one) {
        return "Last heard from at " + one.lastSeenAt + "; it did not shut down cleanly";
    }

    /**
     * One after another, for the reason every loop like this one in Keydra is sequential: a
     * reactive session runs one query at a time.
     */
    private static <T> Uni<Void> each(
            List<T> items, java.util.function.Function<T, Uni<Void>> work) {
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (T item : items) {
            chain = chain.flatMap(ignored -> work.apply(item));
        }
        return chain;
    }
}
