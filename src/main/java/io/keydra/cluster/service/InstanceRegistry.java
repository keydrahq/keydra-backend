package io.keydra.cluster.service;

import io.keydra.about.service.BuildInfo;
import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.cluster.persistence.InstanceRepository;
import io.keydra.common.workload.Workload;
import io.keydra.engine.EngineTraffic;
import io.keydra.events.service.NotificationHub;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Who is running, as opposed to who is doing the chores.
 *
 * <p>Two questions that were being answered by one table. The lease says which instance holds the
 * work that must happen once; it says nothing about how many instances there are, which is exactly
 * what somebody looking at a rolling upgrade wants to know — and what Keydra, watching everybody
 * else's servers, could not say about itself.
 *
 * <p>Every window here is derived from the lease interval rather than configured separately. An
 * instance beats three times per lease, so being unheard-of for two leases is a real absence rather
 * than a slow beat, and being unheard-of for a hundred is a row worth deleting.
 */
@ApplicationScoped
public class InstanceRegistry {

    private static final Logger LOG = Logger.getLogger(InstanceRegistry.class);

    /** How many lease intervals of silence make an instance absent rather than slow. */
    private static final int ABSENT_AFTER_LEASES = 2;

    /** And how many make its row worth removing, which is a different and much longer thing. */
    private static final int FORGET_AFTER_LEASES = 120;

    private final InstanceRepository repository;
    private final BuildInfo build;
    private final NotificationHub hub;
    private final EngineTraffic engines;
    private final Instance<Workload> workloads;
    private final Draining draining;
    private final int leaseSeconds;

    /**
     * Whether this process has managed to announce itself yet.
     *
     * <p>Set on the first announcement that <em>works</em>, not on the first that is attempted: the
     * first one is also the one that clears a drain left behind by a previous process under the
     * same name, and an attempt that failed has cleared nothing.
     */
    private final AtomicBoolean announced = new AtomicBoolean();

    @Inject
    InstanceRegistry(
            InstanceRepository repository,
            BuildInfo build,
            NotificationHub hub,
            EngineTraffic engines,
            Instance<Workload> workloads,
            Draining draining,
            @ConfigProperty(name = "keydra.cluster.lease-seconds") int leaseSeconds) {
        this.repository = repository;
        this.build = build;
        this.hub = hub;
        this.engines = engines;
        this.workloads = workloads;
        this.draining = draining;
        this.leaseSeconds = Math.max(2, leaseSeconds);
    }

    /**
     * What every domain is holding right now, added up.
     *
     * <p>Injected as a set rather than reached for one by one: a class here that knew to ask {@code
     * pubsub}, {@code keys}, {@code console}, {@code tunnels} and {@code events} would know the
     * internals of five domains and would need editing when a sixth started holding something.
     *
     * <p>Every contributor reads an in-memory map, so this is arithmetic rather than I/O — which it
     * has to be, because it runs on the beat and the beat is what keeps the lease. One that throws
     * is skipped: a roster that stopped announcing because a counter was unhappy would take the
     * instance out of the fleet over a number nobody was reading yet.
     */
    private Workload.Snapshot workload() {
        int sockets = 0;
        int streams = 0;
        int jobs = 0;
        Set<Long> targets = new LinkedHashSet<>();
        for (Workload contributor : workloads) {
            Workload.Snapshot snapshot;
            try {
                snapshot = contributor.snapshot();
            } catch (RuntimeException unwilling) {
                LOG.debugf(
                        unwilling,
                        "Could not count what %s is holding",
                        contributor.getClass().getSimpleName());
                continue;
            }
            if (snapshot == null) {
                continue;
            }
            sockets += snapshot.sockets();
            streams += snapshot.streams();
            jobs += snapshot.jobs();
            targets.addAll(snapshot.targets());
        }
        return new Workload.Snapshot(sockets, streams, jobs, targets);
    }

    /**
     * Says this instance is here, and tidies away whatever has been gone for a long time.
     *
     * <p>The sweep rides along rather than having a schedule of its own. Every instance does it,
     * which is not a race worth avoiding: deleting a row twice is deleting it once.
     */
    public Uni<Void> beat() {
        boolean first = !announced.get();
        return repository
                .announce(
                        InstanceId.get(),
                        build.version(),
                        build.commit(),
                        // Carried on the beat rather than counted here, because the counters live
                        // where the traffic does: an instance knows only its own, and the roster
                        // is where all of them meet.
                        hub.publishedCount(),
                        hub.receivedCount(),
                        engines.commandCount(),
                        // Read here rather than by the repository, so the beat carries one answer
                        // taken at one moment instead of five taken as the statement is built.
                        workload(),
                        first)
                .invoke(
                        drainingNow -> {
                            announced.set(true);
                            draining.observed(drainingNow);
                        })
                .call(() -> repository.forgetOlderThan(leaseSeconds * FORGET_AFTER_LEASES))
                .replaceWithVoid();
    }

    /**
     * Asks an instance to stop taking new work, or to start again.
     *
     * <p>Almost never the instance answering the request. It writes the row; the instance it names
     * reads it on its next beat, which is the only way one Keydra can tell another anything.
     *
     * @return false where there is no such instance
     */
    public Uni<Boolean> drain(String id, boolean draining) {
        return repository.setDraining(id, draining);
    }

    /** Removes this instance, for a stop that was asked for rather than one that happened. */
    public Uni<Integer> leave() {
        return repository.forget(InstanceId.get());
    }

    /** Everybody heard from within the window that counts as being here. */
    @WithSession
    public Uni<List<KeydraInstance>> live() {
        return repository.seenWithin(leaseSeconds * ABSENT_AFTER_LEASES);
    }

    /**
     * Everybody still on the books, answering or not.
     *
     * <p>Wider than {@link #live()} on purpose, and it is what phase 61 needed: a roster that shows
     * only who is here cannot show who stopped. The rows have been kept this long since phase 39
     * and nothing had ever read them, so the one record of the thing worth announcing was being
     * thrown away.
     *
     * <p>An instance that left cleanly is not in here either — it removed its own row — which is
     * exactly right: what is on the books and not answering is either dying or dead.
     */
    @WithSession
    public Uni<List<KeydraInstance>> roster() {
        return repository.seenWithin(leaseSeconds * FORGET_AFTER_LEASES);
    }

    /** How long an instance has to have been quiet before it counts as gone rather than slow. */
    public int absentAfterSeconds() {
        return leaseSeconds * ABSENT_AFTER_LEASES;
    }

    /** What this instance calls itself, so a page can mark the one that answered. */
    public String self() {
        return InstanceId.get();
    }
}
