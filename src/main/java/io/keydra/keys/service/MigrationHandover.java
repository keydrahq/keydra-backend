package io.keydra.keys.service;

import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.cluster.service.Draining;
import io.keydra.cluster.service.InstanceRegistry;
import io.keydra.cluster.service.Leadership;
import io.keydra.common.vertx.OwnContext;
import io.keydra.keys.entity.MigrationRun;
import io.keydra.keys.persistence.MigrationRepository;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Migrations that were being walked by an instance that is no longer walking them.
 *
 * <p>A migration lived in the memory of whichever Keydra received the request. Kill that instance
 * halfway through and the keys already moved stayed moved, the rest stayed where they were, and the
 * row said RUNNING for ever — because the only thing that ever corrected such a row was that same
 * instance starting up again, and an instance that is not coming back never does. Somebody had to
 * notice and start it again by hand, which is exactly the moment nobody is watching.
 *
 * <p>Two things make it safe to hand work over. The roster already knows who is here, so an
 * instance that is gone is a fact rather than a guess. And a claim is one statement naming the
 * previous owner, so two instances deciding at the same moment that the same migration is abandoned
 * ends with one of them being told it changed no rows.
 *
 * <p>The sweep runs on whichever instance holds the chores, which is the ordinary rule for work
 * that must happen once. The atomic claim is not what makes that rule unnecessary — it is what
 * makes it safe to be wrong about, for the moment during a handover when two instances both believe
 * they hold them.
 */
@ApplicationScoped
public class MigrationHandover {

    private static final Logger LOG = Logger.getLogger(MigrationHandover.class);

    /**
     * How many at a time.
     *
     * <p>An instance that has just been handed forty migrations is an instance about to fall over,
     * and the sweep comes round again in a few seconds. Taking a few each pass spreads them over
     * however many instances are here rather than piling them all onto whichever noticed first.
     */
    private static final int AT_A_TIME = 3;

    private final Leadership leadership;
    private final InstanceRegistry instances;
    private final Draining draining;
    private final MigrationRepository repository;
    private final KeyMigrationService migrations;
    private final Vertx vertx;
    private final Duration every;
    private final Duration stale;

    private volatile long timer = -1;

    @Inject
    MigrationHandover(
            Leadership leadership,
            InstanceRegistry instances,
            Draining draining,
            MigrationRepository repository,
            KeyMigrationService migrations,
            Vertx vertx,
            @ConfigProperty(name = "keydra.cluster.lease-seconds") int leaseSeconds,
            @ConfigProperty(name = "keydra.keys.migration-stale-seconds", defaultValue = "300")
                    int staleSeconds) {
        this.leadership = leadership;
        this.instances = instances;
        this.draining = draining;
        this.repository = repository;
        this.migrations = migrations;
        this.vertx = vertx;
        this.every = Duration.ofSeconds(Math.max(2, leaseSeconds));
        /*
         * How long a migration may go without writing before it counts as stopped.
         *
         * <p>Generous, and it has to be. A checkpoint is written every fifty batches, so a walk
         * against a slow source or one held to a few keys a second can honestly be minutes between
         * writes — and taking that job away from an instance that is doing it perfectly well would
         * be this feature causing the problem it exists to fix. The precise signal is the roster;
         * this is the backstop for a walk that has hung on an instance that is still answering.
         */
        this.stale = Duration.ofSeconds(Math.max(30, staleSeconds));
    }

    void onStart(@Observes StartupEvent ignored) {
        timer =
                vertx.setPeriodic(
                        every.toMillis(),
                        // Subscribed here rather than inside the sweep, so the sweep is
                        // still something a test can run and read the answer of.
                        id ->
                                sweep().subscribe()
                                        .with(
                                                ignore -> {},
                                                failure ->
                                                        LOG.warnf(
                                                                failure,
                                                                "The migration handover"
                                                                        + " sweep failed")));
    }

    /**
     * Migrations this instance's own name is on that this instance is not walking.
     *
     * <p>The case a restart used to answer by writing them off. A row under this name that is not
     * in the running map is a walk that stopped without an ending — after a crash that is every row
     * the process had, and after a hung walk it is the one that lost its claim. Either way it is
     * already ours, so there is nothing to claim from anybody and nothing to race.
     *
     * <p>On every instance rather than only on the one holding the chores, and that is deliberate:
     * a crashed instance coming back should carry on with its own work at once rather than wait for
     * whichever other instance happens to hold the lease to notice on its behalf.
     */
    private Uni<Integer> reclaimOwn() {
        String me = leadership.instanceId();
        return repository
                .ownedBy(me)
                .map(rows -> rows.stream().filter(run -> !migrations.isRunning(run.id)).toList())
                .flatMap(this::take);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (timer != -1) {
            vertx.cancelTimer(timer);
            timer = -1;
        }
    }

    /**
     * Looks for abandoned migrations and takes as many as this pass allows.
     *
     * <p>Package-private so a test can run one pass rather than wait for a timer, which is the
     * difference between a test that proves the handover and a test that proves the clock.
     */
    Uni<Integer> sweep() {
        // An instance that has been asked to stop takes nothing new. It is about to be stopped, and
        // picking up somebody else's half-finished walk on the way out would mean handing the same
        // work over twice — which is the one behaviour that would make draining an instance worse
        // than simply stopping it.
        if (draining.underWay()) {
            return Uni.createFrom().item(0);
        }
        return OwnContext.call(
                vertx,
                () ->
                        reclaimOwn()
                                .flatMap(mine -> takeAbandoned().map(theirs -> mine + theirs))
                                .onFailure()
                                .invoke(
                                        failure ->
                                                LOG.warnf(
                                                        "Could not look for abandoned migrations:"
                                                                + " %s",
                                                        failure.toString()))
                                .onFailure()
                                .recoverWithItem(0));
    }

    /**
     * Somebody else's work, when there is no longer a somebody else.
     *
     * <p>Only on the instance holding the chores, which is the ordinary rule for work that must
     * happen once. The claim below makes it safe to be wrong about that for the moment during a
     * handover when two instances both believe they hold them.
     */
    private Uni<Integer> takeAbandoned() {
        if (!leadership.isLeader()) {
            return Uni.createFrom().item(0);
        }
        return instances
                .live()
                .flatMap(live -> repository.abandoned(idsOf(live), Instant.now().minus(stale)))
                // Not the ones this instance is walking right now. They are in the roster and their
                // claim is fresh, so they cannot be in that list — but a walk that has gone quiet
                // for longer than the stale window is exactly the case, and taking a job away from
                // ourselves would be this sweep racing itself.
                .map(rows -> rows.stream().filter(run -> !migrations.isRunning(run.id)).toList())
                .flatMap(this::take);
    }

    private Uni<Integer> take(List<MigrationRun> abandoned) {
        if (abandoned.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        String me = leadership.instanceId();
        Uni<Integer> taken = Uni.createFrom().item(0);
        for (MigrationRun run : abandoned.stream().limit(AT_A_TIME).toList()) {
            // One after another rather than all at once: each is a claim and then a load of two
            // profiles, and every one of those wants a session of its own.
            taken =
                    taken.flatMap(
                            sofar ->
                                    repository
                                            .claim(run.id, run.instanceId, me)
                                            .flatMap(
                                                    won -> {
                                                        if (!won) {
                                                            // Somebody else got there first, which
                                                            // is the mechanism working rather than
                                                            // failing.
                                                            return Uni.createFrom().item(sofar);
                                                        }
                                                        LOG.infof(
                                                                "Migration %s was left by %s;"
                                                                        + " %s is carrying it on",
                                                                run.id,
                                                                run.instanceId == null
                                                                        ? "an unnamed instance"
                                                                        : run.instanceId,
                                                                me);
                                                        run.instanceId = me;
                                                        // The statement above incremented this in
                                                        // the database; the entity in hand still
                                                        // holds the number before it. Without this
                                                        // the running job publishes the old count,
                                                        // and a list that overlays what is running
                                                        // onto the rows shows a job that was
                                                        // resumed saying it was not.
                                                        run.resumed = run.resumed + 1;
                                                        return migrations
                                                                .resume(run)
                                                                .map(
                                                                        started ->
                                                                                Boolean.TRUE.equals(
                                                                                                started)
                                                                                        ? sofar + 1
                                                                                        : sofar);
                                                    }));
        }
        return taken;
    }

    private static Set<String> idsOf(List<KeydraInstance> live) {
        return live.stream().map(instance -> instance.id).collect(Collectors.toSet());
    }
}
