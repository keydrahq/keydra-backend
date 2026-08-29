package io.keydra.cluster.service;

import io.keydra.cluster.dto.LeadershipChanged;
import io.keydra.cluster.persistence.LeaseRepository;
import io.keydra.common.vertx.OwnContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which instance does the work that must only be done once.
 *
 * <p>A lease in the database that is already there, rather than an election protocol: PostgreSQL is
 * already required, already the thing every instance shares, and a lease is one row. Something to
 * coordinate through would be a second thing to operate for a guarantee this does not need — the
 * worst case of losing the lease for a moment is a schedule that runs a moment late.
 *
 * <p>Held for a few seconds and renewed three times as often, so an instance that is killed loses
 * it within the lease rather than for ever. Nobody promotes anybody: whoever asks first once the
 * old lease has run out gets it.
 *
 * <p>{@link #isLeader()} is deliberately not the last answer the database gave. An instance that
 * cannot reach the database still believes what it was told a moment ago, and a moment can outlast
 * the lease — so the belief expires on its own clock too. What that costs is a few seconds of
 * nobody running the chores during a handover, which is the right side to be wrong on.
 */
@ApplicationScoped
public class Leadership {

    private static final Logger LOG = Logger.getLogger(Leadership.class);

    /**
     * The one lease there is: the work nobody asked for at that moment.
     *
     * <p>Schedules firing, rules being decided, the sweep that tidies up after a crash. They move
     * together because they are all "what this application does when nobody is looking", and
     * splitting them would mean two instances each half in charge for no benefit anybody could
     * name.
     */
    public static final String CHORES = "chores";

    private final LeaseRepository leases;
    private final InstanceRegistry instances;
    private final Draining draining;
    private final Vertx vertx;
    private final Event<LeadershipChanged> changes;
    private final String instanceId;
    private final int leaseSeconds;

    /** Whether the database said yes, and when it said it — on a clock nothing else can move. */
    private volatile boolean holding;

    private volatile long heldSince;

    /** One beat at a time: a slow database must not queue up beats behind each other. */
    private final AtomicBoolean beating = new AtomicBoolean();

    private volatile Long timer;

    @Inject
    Leadership(
            LeaseRepository leases,
            InstanceRegistry instances,
            Draining draining,
            Vertx vertx,
            Event<LeadershipChanged> changes,
            @ConfigProperty(name = "keydra.cluster.lease-seconds") int leaseSeconds) {
        this.leases = leases;
        this.instances = instances;
        this.draining = draining;
        this.vertx = vertx;
        this.changes = changes;
        this.leaseSeconds = Math.max(2, leaseSeconds);
        this.instanceId = InstanceId.get();
    }

    void onStart(@Observes StartupEvent ignored) {
        long every = Math.max(500, this.leaseSeconds * 1000L / 3);
        LOG.infof(
                "Instance %s, renewing a %d second lease every %d ms",
                instanceId, leaseSeconds, every);
        beat();
        timer = vertx.setPeriodic(every, id -> beat());
    }

    /**
     * Gives the lease back on the way out.
     *
     * <p>Blocking here is allowed and appropriate — shutdown is not an event loop — and failing
     * here is survivable: an unreleased lease expires on its own, which is exactly what happens
     * when an instance is killed rather than asked.
     */
    void onStop(@Observes ShutdownEvent ignored) {
        if (timer != null) {
            vertx.cancelTimer(timer);
        }
        // Removed on the way out whether or not the lease was held: being here and doing the
        // chores are separate facts, and a clean stop should not leave a row somebody has to wait
        // out.
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            instances
                                    .leave()
                                    .ifNoItem()
                                    .after(Duration.ofSeconds(2))
                                    .recoverWithItem(0));
        } catch (Throwable failure) {
            LOG.debugf(failure, "Could not remove this instance from the roster; it will age out");
        }
        if (!holding) {
            return;
        }
        try {
            // Bounded, because this is the shutdown path: a database that has stopped
            // answering must not turn stopping into hanging. Two seconds is longer than the
            // write takes and shorter than anybody's patience.
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            leases.release(CHORES, instanceId)
                                    .ifNoItem()
                                    .after(Duration.ofSeconds(2))
                                    .fail());
            LOG.infof("Instance %s gave up the chores", instanceId);
        } catch (Throwable failure) {
            LOG.debugf(failure, "Could not give the lease up; it will expire on its own");
        }
    }

    /**
     * Whether this instance is the one that does the chores.
     *
     * <p>Asked at the moment the work would happen rather than remembered from when it was
     * arranged, because that is the moment the answer has to be true.
     */
    public boolean isLeader() {
        return holding && System.nanoTime() - heldSince < leaseSeconds * 1_000_000_000L;
    }

    /**
     * What this instance calls itself. Written on the rows it starts, and shown on the About page.
     */
    public String instanceId() {
        return instanceId;
    }

    /** Which instance holds the chores at this moment, whoever is being asked. */
    public Uni<String> holder() {
        return leases.holder(CHORES);
    }

    private void beat() {
        if (!beating.compareAndSet(false, true)) {
            return;
        }
        OwnContext.run(
                vertx,
                () ->
                        // On the same beat rather than on a timer of its own: an instance that can
                        // still renew its lease is an instance that is still here, and the two
                        // facts arriving apart would be two answers to one question.
                        //
                        // Announcing first and deciding afterwards, so an instance that has been
                        // asked to stop acts on the beat it learns about it rather than the next
                        // one. What put the claim first is kept rather than lost: a roster this
                        // instance cannot write must not cost it the lease, so a failed
                        // announcement is recovered from and the decision is taken anyway —
                        // believing whatever the last beat that worked said.
                        instances
                                .beat()
                                .onFailure()
                                .recoverWithNull()
                                .onItem()
                                .transformToUni(announced -> decide())
                                // Not reaching the database is not proof somebody else has the
                                // lease, but it is proof this instance cannot renew its own —
                                // and a lease it cannot renew is one it is about to lose.
                                .onFailure()
                                .invoke(
                                        failure ->
                                                LOG.warnf(
                                                        "Could not renew the lease: %s",
                                                        failure.toString()))
                                .onFailure()
                                .recoverWithItem(false)
                                .invoke(this::settle)
                                .eventually(() -> beating.set(false))
                                .replaceWithVoid(),
                failure -> {
                    beating.set(false);
                    LOG.error("Could not renew the lease", failure);
                });
    }

    /**
     * Whether this instance holds the chores after this beat.
     *
     * <p>Ordinarily by asking for them. An instance that is draining asks for nothing and gives
     * back what it has: the chores stop for the time between two beats rather than for the rest of
     * a lease, which is the entire reason to drain an instance before stopping it instead of just
     * stopping it.
     *
     * <p>And it does not ask again while draining, or it would give the lease up and claim it back
     * a beat later, which is a handover to itself.
     */
    private Uni<Boolean> decide() {
        if (!draining.underWay()) {
            return leases.claim(CHORES, instanceId, leaseSeconds);
        }
        if (!holding) {
            return Uni.createFrom().item(false);
        }
        LOG.infof("Instance %s is draining and is giving the chores back", instanceId);
        return leases.release(CHORES, instanceId)
                .replaceWith(false)
                // A lease that could not be released expires on its own, which is what happens to
                // an instance that is killed. Failing the beat over it would cost the roster write
                // too, for a courtesy.
                .onFailure()
                .recoverWithItem(false);
    }

    private void settle(boolean nowHolding) {
        if (nowHolding) {
            heldSince = System.nanoTime();
        }
        if (nowHolding == holding) {
            return;
        }
        holding = nowHolding;
        LOG.infof(
                nowHolding
                        ? "Instance %s has taken on the chores"
                        : "Instance %s is no longer doing the chores",
                instanceId);
        // Fired from the beat's own context; every observer that needs a session takes a
        // context of its own, because this one belongs to the transaction that just ended.
        changes.fire(new LeadershipChanged(CHORES, nowHolding, instanceId));
    }
}
