package io.keydra.cluster.service;

import io.keydra.alerts.service.InstanceNotices;
import io.keydra.cluster.persistence.ReachabilityRepository;
import io.keydra.common.reach.Reachable;
import io.keydra.common.vertx.OwnContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Asks the things outside Keydra whether they are there, and writes down what they said.
 *
 * <p>Phase 40 refused to probe on page load and was right: ten people watching a status page would
 * be ten times the load of one, aimed at somebody else's service. That is an objection to the page
 * load rather than to the probe. So the asking happens here, once, on a slow clock, and the page
 * reads a row.
 *
 * <p>On the instance holding the chores, asked at the moment the work would happen — the rule every
 * job that must run once follows. A fleet of five that all checked would quintuple somebody's
 * traffic to draw one page.
 *
 * <p>One thing at a time rather than all at once. A reactive session runs one query at a time, so
 * issuing them concurrently produces "session is currently executing another query" instead of a
 * faster answer; and ten simultaneous requests at somebody else's service is the shape of thing
 * this class exists to avoid.
 */
@ApplicationScoped
public class Reachability {

    private static final Logger LOG = Logger.getLogger(Reachability.class);

    private final Vertx vertx;
    private final Instance<Reachable> kinds;
    private final ReachabilityRepository repository;
    private final InstanceNotices notices;
    private final Leadership leadership;
    private final Duration interval;
    private final Duration minimumGap;
    private final Duration historyKept;

    /** One walk at a time on this instance, however many timers or buttons ask for one. */
    private final AtomicBoolean walking = new AtomicBoolean();

    private long timerId = -1;

    @Inject
    Reachability(
            Vertx vertx,
            Instance<Reachable> kinds,
            ReachabilityRepository repository,
            InstanceNotices notices,
            Leadership leadership,
            @ConfigProperty(name = "keydra.reachability.interval", defaultValue = "10m")
                    Duration interval,
            @ConfigProperty(name = "keydra.reachability.minimum-gap", defaultValue = "30s")
                    Duration minimumGap,
            @ConfigProperty(name = "keydra.reachability.history-kept", defaultValue = "90d")
                    Duration historyKept) {
        this.vertx = vertx;
        this.kinds = kinds;
        this.repository = repository;
        this.notices = notices;
        this.leadership = leadership;
        this.interval = interval;
        this.minimumGap = minimumGap;
        this.historyKept = historyKept;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (interval.isZero() || interval.isNegative()) {
            LOG.debug("Reachability checking is off");
            return;
        }
        timerId = vertx.setPeriodic(interval.toMillis(), id -> onTheClock());
        LOG.debugf("Asking what this instance reaches every %s", interval);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    private void onTheClock() {
        if (!leadership.isLeader()) {
            return;
        }
        // On a context of its own, because a timer that joined whatever context it fired on would
        // be joining a session a finished request has already closed.
        OwnContext.run(
                vertx,
                this::ask,
                failure ->
                        LOG.warnf(
                                "Could not finish the reachability walk: %s", failure.toString()));
    }

    /**
     * Asks everything now, because somebody pressed the button.
     *
     * <p>On whichever instance the request arrived at rather than on the leader: the point of the
     * button is an answer now, and handing the work to another process would make "now" mean
     * "within ten minutes" again.
     *
     * <p>Refused when the last answer on record is newer than the minimum gap. A button that can be
     * held down is a way to make Keydra hammer somebody else's service from a page that only needs
     * {@code instance:read}, and the check is against the table rather than against a field here so
     * that a fleet of five does not allow five presses.
     */
    public Uni<Boolean> checkNow() {
        return repository
                .newestCheck()
                .flatMap(
                        newest ->
                                newest.isAfter(Instant.now().minus(minimumGap))
                                        ? Uni.createFrom().item(false)
                                        : ask().replaceWith(true));
    }

    /**
     * Asks everything now, whoever is asking and however recently it was last asked.
     *
     * <p>The unguarded form. {@link #checkNow()} is the one an endpoint calls, and the guard is
     * there rather than here because it is about buttons rather than about walking: the timer has
     * its own interval and does not need to be told to wait.
     *
     * <p>One walk at a time on this instance whatever calls it, because a second walk started while
     * the first is halfway through would be two requests at every service for one answer.
     */
    public Uni<Integer> ask() {
        if (!walking.compareAndSet(false, true)) {
            return Uni.createFrom().item(0);
        }
        List<Reachable> all = new ArrayList<>();
        kinds.forEach(all::add);

        Uni<Integer> chain = Uni.createFrom().item(0);
        for (Reachable kind : all) {
            chain = chain.flatMap(sofar -> oneKind(kind).map(asked -> sofar + asked));
        }
        return chain.call(this::prune).eventually(() -> walking.set(false));
    }

    /**
     * Forgets what happened long enough ago that nobody is asking.
     *
     * <p>On the walk rather than on a timer of its own, the way the roster's tidying rides on the
     * beat: the walk already runs on the instance holding the chores, and a second timer would be a
     * second thing to reason about for one delete a week.
     *
     * <p>And it <em>is</em> a chore, which is worth saying because phase 61 introduced the first
     * work here that deliberately is not one. That exception exists because what it watches for
     * includes nobody being in charge. Nothing about pruning a history is watching for that, so it
     * asks like everything else — a fleet of five each deleting the same rows is five transactions
     * doing one deletion.
     *
     * <p>Never fails the walk. What it is tidying is already recorded, and a delete that could not
     * run must not cost the answers the walk just wrote.
     */
    private Uni<Void> prune() {
        return repository
                .forgetHistoryBefore(Instant.now().minus(historyKept))
                .onFailure()
                .recoverWithItem(0)
                .replaceWithVoid();
    }

    private Uni<Integer> oneKind(Reachable kind) {
        return kind.subjects()
                .flatMap(
                        subjects -> {
                            Uni<Integer> chain =
                                    repository
                                            .forgetAllBut(
                                                    kind.kind(),
                                                    subjects.stream()
                                                            .map(Reachable.Subject::id)
                                                            .toList())
                                            .replaceWith(0);
                            for (Reachable.Subject subject : subjects) {
                                if (!subject.enabled()) {
                                    // Off because somebody turned it off. Asking anyway would be
                                    // making a request on behalf of a decision to stop making them.
                                    continue;
                                }
                                chain =
                                        chain.flatMap(
                                                sofar ->
                                                        ask(kind, subject).map(one -> sofar + one));
                            }
                            return chain;
                        })
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not list what to check for %s", kind.kind());
                            return 0;
                        });
    }

    /**
     * Asks one, writes down what it said, and announces it if that was a change.
     *
     * <p>The edge and never the state. Something that has been down since Tuesday is one message on
     * Tuesday rather than one every ten minutes for a week.
     *
     * <p>Whether it is a change is decided by the write itself rather than by a read before it. The
     * leader's clock and somebody pressing the button on another instance can walk the same subject
     * at the same moment; a read-then-write would let both see the old answer and both announce the
     * same edge.
     *
     * <p>A subject nobody has asked before is written and is not a change — announcing it would
     * make adding a destination an alarm.
     */
    private Uni<Integer> ask(Reachable kind, Reachable.Subject subject) {
        Instant asked = Instant.now();
        return kind.check(subject.id())
                .onFailure()
                .recoverWithItem(failure -> Reachable.Outcome.not("The check itself failed to run"))
                .flatMap(
                        outcome ->
                                repository
                                        .record(
                                                kind.kind(),
                                                subject.id(),
                                                subject.name(),
                                                outcome.ok(),
                                                outcome.detail(),
                                                asked)
                                        .call(changed -> announce(kind, subject, changed, outcome))
                                        .replaceWith(1));
    }

    private Uni<Void> announce(
            Reachable kind, Reachable.Subject subject, boolean changed, Reachable.Outcome outcome) {
        return changed
                ? notices.changed(
                        kind.describedAs(), subject.name(), outcome.ok(), outcome.detail())
                : Uni.createFrom().voidItem();
    }

    /**
     * What has changed, newest first, as the page reads it.
     *
     * <p>Bounded here rather than by the caller alone, because the ceiling is about what a page can
     * show rather than about what somebody asks for.
     */
    public Uni<java.util.List<io.keydra.cluster.dto.ClusterDtos.ReachabilityEventSummary>> history(
            String kind, Long subjectId, int limit) {
        return repository
                .history(kind, subjectId, Math.clamp(limit, 1, 500))
                .map(
                        rows ->
                                rows.stream()
                                        .map(
                                                row ->
                                                        new io.keydra.cluster.dto.ClusterDtos
                                                                .ReachabilityEventSummary(
                                                                row.kind,
                                                                row.subjectId,
                                                                row.name,
                                                                row.at,
                                                                row.ok,
                                                                row.detail))
                                        .toList());
    }
}
