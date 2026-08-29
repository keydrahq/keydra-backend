package io.keydra.keys.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeyChange;
import io.keydra.engine.KeyspaceEvents;
import io.keydra.engine.KeyspaceNotice;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.keys.dto.KeyspaceWatchState;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Listens to what a target says about its own changes, for as long as somebody is looking.
 *
 * <p>Until this existed the key browser learned about exactly the changes Keydra made, which is the
 * smallest fraction of the changes a cache sees. The server has been announcing all of them since
 * Redis 2.8; nobody was listening.
 *
 * <p>Three decisions are worth stating because each of them could sensibly have gone the other way.
 *
 * <p><b>Not the subscription registry.</b> That holds one subscription per target and replaces it
 * on every request, which is right for a page where somebody states the channels they want — and
 * would mean that opening the key browser silently closed the channels somebody else was watching
 * on the Pub/Sub page. A keyspace watch is its own subscription on its own connection.
 *
 * <p><b>Leases rather than ownership.</b> A browser can vanish — a closed laptop, a lost network —
 * and a watch owned by a page that never says goodbye is a connection held open forever. So a
 * caller takes a lease with a deadline and renews it while it is looking, and a watch whose last
 * lease lapses stops on its own. Two people browsing one target is one subscription and two leases;
 * the second one leaving does not end the first one's watch.
 *
 * <p><b>Coalesced.</b> One notification per mutation would be a broadcast per write, to every tab
 * watching that target, on a server doing thousands a second. What goes out instead is at most one
 * message per target per interval, saying how many changes there were, of which kinds, and a
 * bounded sample of the keys.
 *
 * <p>The timer that empties the batches is not on a context of its own, which every other timer
 * here is. The rule exists because a timer that reaches Hibernate joins whatever context it fired
 * on and finds a session a finished request has closed; this one reaches the notification hub and
 * nothing else, and giving it a database session it never uses would be the ritual without the
 * reason.
 *
 * <p>Not asked of {@link io.keydra.cluster.service.Leadership}, unlike the jobs that must happen
 * once. A watch serves the browsers attached to <em>this</em> instance, so each instance opens the
 * watches its own viewers need. Two instances watching one target both broadcast, and a viewer may
 * hear the same news twice — which costs one extra refetch that the coalescing already bounds. The
 * alternative is a watch held by whichever instance holds the lease, which is a watch that has to
 * survive that instance going away, for a thing that costs a refetch when it does not.
 */
@ApplicationScoped
public class KeyspaceWatch {

    private static final Logger LOG = Logger.getLogger(KeyspaceWatch.class);

    /**
     * How many distinct key names travel with a batch.
     *
     * <p>Bounded because the point is to be cheap. It is also why the count goes out beside the
     * sample: a browser filtering on {@code session:*} can decide from the sample that its prefix
     * moved, but when the sample overflowed it cannot decide that its prefix did <em>not</em> — so
     * it asks again. Guessing the other way is a list that quietly stops being true.
     */
    private static final int SAMPLE = 20;

    /**
     * How long the target's setting is believed without asking again.
     *
     * <p>A lease is renewed every three-quarters of a minute per tab, and asking the server what
     * its setting is on each renewal would be a {@code CONFIG GET} per browsing tab per renewal —
     * for an answer that changes when somebody administers the server, which is not often. Short
     * enough that a page notices within a minute when the setting is turned off under it, which is
     * the case worth noticing: a list that has quietly stopped keeping up.
     */
    private static final Duration NOTICE_BELIEVED = Duration.ofSeconds(45);

    /** Which database of which target, which is what a watch is on. */
    private record Watched(Long connectionId, int database) {}

    /**
     * One open watch: what holds it, who is holding it, and what has arrived since the last send.
     */
    private static final class Open {
        private final Cancellable cancellable;
        private final Map<String, Instant> leases = new ConcurrentHashMap<>();
        private final Map<String, Long> kinds = new ConcurrentHashMap<>();
        private final Set<String> sample = new LinkedHashSet<>();

        /**
         * Which keys each lease is looking at, and therefore which ones cannot be sampled away.
         *
         * <p>Per lease rather than one set, because a lease lapsing has to take its own keys with
         * it and leave everybody else's — the same reason the watch itself is reference counted.
         */
        private final Map<String, Set<String>> watchedByLease = new ConcurrentHashMap<>();

        /** What happened to one of those since the last send, newest event winning. */
        private final Map<String, String> touched = new ConcurrentHashMap<>();

        private long changes;
        private volatile KeyspaceNotice notice;
        private volatile Instant noticeReadAt;

        private Open(Cancellable cancellable) {
            this.cancellable = cancellable;
        }

        private boolean noticeIsStale() {
            return notice == null
                    || noticeReadAt == null
                    || noticeReadAt.isBefore(Instant.now().minus(NOTICE_BELIEVED));
        }

        private void remember(KeyspaceNotice read) {
            notice = read;
            noticeReadAt = Instant.now();
        }

        /** Every key anybody holding this watch is looking at. */
        private boolean isWatched(String key) {
            for (Set<String> keys : watchedByLease.values()) {
                if (keys.contains(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final Vertx vertx;
    private final ConnectionService connections;
    private final EngineSelector engines;
    private final NotificationHub hub;
    private final Duration lease;
    private final Duration flushInterval;
    private final Map<Watched, Open> watches = new ConcurrentHashMap<>();

    private long timerId = -1;

    @Inject
    KeyspaceWatch(
            Vertx vertx,
            ConnectionService connections,
            EngineSelector engines,
            NotificationHub hub,
            @ConfigProperty(name = "keydra.keyspace.lease", defaultValue = "2m") Duration lease,
            @ConfigProperty(name = "keydra.keyspace.flush-interval", defaultValue = "2s")
                    Duration flushInterval) {
        this.vertx = vertx;
        this.connections = connections;
        this.engines = engines;
        this.hub = hub;
        this.lease = lease;
        this.flushInterval = flushInterval;
    }

    void onStart(@Observes StartupEvent ignored) {
        timerId = vertx.setPeriodic(flushInterval.toMillis(), id -> tick());
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (timerId != -1) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
        Set.copyOf(watches.keySet()).forEach(this::stop);
    }

    /**
     * Takes or renews a lease, opening the watch if this is the first one.
     *
     * <p>One call for both because a renewal and a first request are the same sentence — <em>I am
     * still looking at this</em> — and a caller whose lease lapsed while its tab was in the
     * background would otherwise have to notice that and say something different.
     */
    public Uni<KeyspaceWatchState> hold(Long connectionId, int database, String leaseId) {
        return hold(connectionId, database, leaseId, null);
    }

    /**
     * The same, saying which keys this lease is looking at.
     *
     * <p>The sample in a batch is bounded, so a caller watching one key cannot tell from an
     * overflowed sample that its key did <em>not</em> change — and re-reading a value every two
     * seconds to find out would be heavier than the polling this replaced, on the page where the
     * values are largest. A key named here is always in the message when it moves, whatever the
     * sample did.
     *
     * @param keys what this lease is looking at, or null for a caller that only wants the list
     */
    public Uni<KeyspaceWatchState> hold(
            Long connectionId, int database, String leaseId, Set<String> keys) {
        return connections
                .load(connectionId)
                .flatMap(profile -> hold(profile, connectionId, database, leaseId, keys));
    }

    private Uni<KeyspaceWatchState> hold(
            ConnectionProfile profile,
            Long connectionId,
            int database,
            String leaseId,
            Set<String> keys) {
        KeyspaceEvents events = engines.forProfile(profile).keyspaceEvents().orElse(null);
        if (events == null) {
            return Uni.createFrom().item(unsupported(connectionId, database));
        }
        Watched watched = new Watched(connectionId, database);
        Open open = watches.computeIfAbsent(watched, key -> start(profile, events, key));
        String held = leaseId == null || leaseId.isBlank() ? UUID.randomUUID().toString() : leaseId;
        Instant until = Instant.now().plus(lease);
        open.leases.put(held, until);
        // Replaced rather than added to: what a lease is looking at now is what it says now, and a
        // panel that moved from one key to another must not go on being told about the first.
        if (keys == null || keys.isEmpty()) {
            open.watchedByLease.remove(held);
        } else {
            open.watchedByLease.put(held, Set.copyOf(keys));
        }
        return notice(profile, events, open)
                .map(notice -> describe(watched, open, notice, held, until));
    }

    /** Gives a lease back, which stops the watch when it was the last one. */
    public boolean release(Long connectionId, int database, String leaseId) {
        Watched watched = new Watched(connectionId, database);
        Open open = watches.get(watched);
        if (open == null || leaseId == null) {
            return false;
        }
        boolean held = open.leases.remove(leaseId) != null;
        open.watchedByLease.remove(leaseId);
        if (open.leases.isEmpty()) {
            stop(watched);
        }
        return held;
    }

    /** What is being watched and whether the target is saying anything, without taking a lease. */
    public Uni<KeyspaceWatchState> state(Long connectionId, int database) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile -> {
                            KeyspaceEvents events =
                                    engines.forProfile(profile).keyspaceEvents().orElse(null);
                            if (events == null) {
                                return Uni.createFrom().item(unsupported(connectionId, database));
                            }
                            Watched watched = new Watched(connectionId, database);
                            Open open = watches.get(watched);
                            return notice(profile, events, open)
                                    .map(notice -> describe(watched, open, notice, null, null));
                        });
    }

    /**
     * Turns the target's announcements on.
     *
     * <p>A different act from watching and it carries a different permission — this changes a
     * running server's configuration, and the endpoint says so. Kept here rather than on the
     * settings page because what it sets is not a setting anybody would find by name: the value is
     * a string of flags, and the useful form of this question is "make the changes audible", not
     * "set notify-keyspace-events to AE".
     */
    public Uni<KeyspaceWatchState> announce(Long connectionId, int database) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile -> {
                            KeyspaceEvents events =
                                    engines.forProfile(profile)
                                            .keyspaceEvents()
                                            .orElseThrow(
                                                    () ->
                                                            new UnsupportedOperationException(
                                                                    "This target does not announce"
                                                                            + " its own changes"));
                            return events.announce(profile);
                        })
                .invoke(
                        ignored -> {
                            // The answer that was believed is the one this just made wrong.
                            Open open = watches.get(new Watched(connectionId, database));
                            if (open != null) {
                                open.noticeReadAt = null;
                            }
                        })
                .flatMap(ignored -> state(connectionId, database));
    }

    /**
     * What the target's setting says, asked of the target only when it is worth asking again.
     *
     * <p>Believed for {@link #NOTICE_BELIEVED} where a watch is open, because the alternative is a
     * round trip per tab per renewal for an answer nobody changes by the minute. Asked outright
     * where there is no watch: that is somebody looking at the state before deciding to watch, and
     * a cached answer would be the one thing they came for.
     */
    private Uni<KeyspaceNotice> notice(
            ConnectionProfile profile, KeyspaceEvents events, Open open) {
        if (open == null) {
            return events.notices(profile);
        }
        if (!open.noticeIsStale()) {
            return Uni.createFrom().item(open.notice);
        }
        return events.notices(profile).invoke(open::remember);
    }

    private Open start(ConnectionProfile profile, KeyspaceEvents events, Watched watched) {
        Cancellable cancellable =
                events.watch(profile, watched.database())
                        .subscribe()
                        .with(
                                change -> record(watched, change),
                                failure -> {
                                    LOG.debugf(
                                            failure,
                                            "The keyspace watch on %d db %d ended",
                                            watched.connectionId(),
                                            (Object) watched.database());
                                    watches.remove(watched);
                                });
        LOG.debugf(
                "Watching the keyspace of %d db %d",
                watched.connectionId(), (Object) watched.database());
        return new Open(cancellable);
    }

    /**
     * Notes a change without sending anything.
     *
     * <p>Synchronized on the batch rather than on the map: what is accumulating is one target's
     * counters, the timer empties them, and the two must not interleave — a sample read while it is
     * being added to is the kind of fault that appears once a week under load and never in a test.
     */
    private void record(Watched watched, KeyChange change) {
        Open open = watches.get(watched);
        if (open == null) {
            return;
        }
        synchronized (open) {
            open.changes++;
            open.kinds.merge(change.event(), 1L, Long::sum);
            if (open.sample.size() < SAMPLE) {
                open.sample.add(change.key());
            }
            // Beside the sample rather than inside it. A key somebody has open is the one key that
            // must not be dropped when the sample fills, and the newest event wins because what a
            // panel does about "set then deleted" is what it does about "deleted".
            if (open.isWatched(change.key())) {
                open.touched.put(change.key(), change.event());
            }
        }
    }

    /** Sends what has piled up, and lets go of the watches nobody is holding any more. */
    private void tick() {
        Instant now = Instant.now();
        for (Map.Entry<Watched, Open> entry : watches.entrySet()) {
            Open open = entry.getValue();
            open.leases.entrySet().removeIf(held -> held.getValue().isBefore(now));
            if (open.leases.isEmpty()) {
                stop(entry.getKey());
                continue;
            }
            flush(entry.getKey(), open);
        }
    }

    private void flush(Watched watched, Open open) {
        long changes;
        Map<String, Long> kinds;
        Set<String> keys;
        Map<String, String> touched;
        synchronized (open) {
            if (open.changes == 0) {
                return;
            }
            changes = open.changes;
            kinds = new LinkedHashMap<>(open.kinds);
            keys = Set.copyOf(open.sample);
            touched = Map.copyOf(open.touched);
            open.changes = 0;
            open.kinds.clear();
            open.sample.clear();
            open.touched.clear();
        }
        /*
         * KeysChanged, and not a category of its own. What a browser does about a key it did not
         * change is exactly what it does about one it did — ask again — and the pages that listen
         * for this have listened since phase 2. A second category would be a second thing to
         * subscribe to that means the same as the first.
         *
         * The source says where the news came from, because the two are not the same news: one is
         * this console's own work and the other is the application whose cache this is. A page that
         * wanted to tell somebody "something else is writing to this" needs to be able to tell.
         */
        hub.broadcast(
                NotificationCategory.KEYS_CHANGED,
                watched.connectionId(),
                Map.of(
                        "connectionId",
                        watched.connectionId(),
                        "database",
                        watched.database(),
                        "source",
                        "target",
                        "operation",
                        "keyspace-event",
                        "affected",
                        changes,
                        "kinds",
                        kinds,
                        "keys",
                        keys,
                        "sampled",
                        keys.size() < changes,
                        // What happened to the keys somebody has open, which the sample above is
                        // not allowed to answer for: an empty map means none of them moved, and
                        // that is a fact rather than a silence.
                        "watched",
                        touched));
    }

    private void stop(Watched watched) {
        Open open = watches.remove(watched);
        if (open == null) {
            return;
        }
        open.cancellable.cancel();
        LOG.debugf(
                "Stopped watching the keyspace of %d db %d",
                watched.connectionId(), (Object) watched.database());
    }

    private KeyspaceWatchState describe(
            Watched watched, Open open, KeyspaceNotice notice, String leaseId, Instant until) {
        return new KeyspaceWatchState(
                watched.connectionId(),
                watched.database(),
                true,
                notice.delivers(),
                notice.setting(),
                notice.wouldBecome(),
                open != null,
                open == null ? 0 : open.leases.size(),
                leaseId,
                until);
    }

    /** What a store that changes silently answers, which is the truth rather than an error. */
    private static KeyspaceWatchState unsupported(Long connectionId, int database) {
        return new KeyspaceWatchState(
                connectionId, database, false, false, "", "", false, 0, null, null);
    }
}
