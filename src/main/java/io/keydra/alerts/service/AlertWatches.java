package io.keydra.alerts.service;

import io.keydra.alerts.entity.AlertRule;
import io.keydra.alerts.persistence.AlertRuleRepository;
import io.keydra.cluster.dto.LeadershipChanged;
import io.keydra.cluster.service.Leadership;
import io.keydra.common.vertx.OwnContext;
import io.keydra.monitoring.service.MetricsSampler;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Keeps the targets that have rules being sampled.
 *
 * <p>Sampling is opt-in because it costs a round trip per interval, and writing a rule is the
 * clearest opting-in there is: somebody has said, in writing, that they want to be told about this
 * server. Without this the rules would only work while a dashboard was open — which is exactly when
 * nobody needs them.
 *
 * <p>Held as a reason rather than as a switch, so a person closing their dashboard does not stop
 * the rules, and the rules do not stop a person's dashboard.
 *
 * <p>Only the instance holding the chores holds these watches. Sampling on a rule's behalf exists
 * so the rules can be decided, and the rules are decided in one place; two instances each polling
 * every watched server would double what the watching costs the server for nothing anybody asked
 * for. A dashboard someone has open is a different reason and is unaffected.
 *
 * <p>The rules are re-read on a slow tick as well as on every write, because the write can happen
 * on another instance. Nothing about a rule that has not changed is disturbed by the re-read.
 */
@ApplicationScoped
public class AlertWatches {

    private static final Logger LOG = Logger.getLogger(AlertWatches.class);

    private final AlertRuleRepository repository;
    private final AlertRegistry registry;
    private final Baselines baselines;
    private final MetricsSampler sampler;
    private final Leadership leadership;
    private final Vertx vertx;
    private final int reconcileSeconds;

    /** The targets this bean is holding a watch on, so it releases exactly what it took. */
    private final Set<Long> holding = ConcurrentHashMap.newKeySet();

    /** The re-read timer, kept so it can be stopped. See {@link #onStop}. */
    private volatile Long reconcileTimer;

    @Inject
    AlertWatches(
            AlertRuleRepository repository,
            AlertRegistry registry,
            Baselines baselines,
            MetricsSampler sampler,
            Leadership leadership,
            Vertx vertx,
            @ConfigProperty(name = "keydra.cluster.reconcile-seconds") int reconcileSeconds) {
        this.repository = repository;
        this.registry = registry;
        this.baselines = baselines;
        this.sampler = sampler;
        this.leadership = leadership;
        this.vertx = vertx;
        this.reconcileSeconds = Math.max(5, reconcileSeconds);
    }

    void onStart(@Observes StartupEvent ignored) {
        try {
            VertxContextSupport.subscribeAndAwait(this::reload);
        } catch (Throwable failure) {
            // Rules that cannot be loaded are rules that will not fire, which is worth an error
            // in the log and not worth refusing to start for: an instance that says why is
            // easier to fix than one that will not come up.
            LOG.error("Could not load the alert rules", failure);
        }
        reconcileTimer =
                vertx.setPeriodic(
                        reconcileSeconds * 1000L,
                        timer ->
                                OwnContext.run(
                                        vertx,
                                        this::reload,
                                        failure ->
                                                LOG.debugf(
                                                        failure,
                                                        "Could not re-read the alert rules")));
    }

    /**
     * Stops the re-reading.
     *
     * <p>Vert.x outlives the application it was started for, so a timer nobody cancels goes on
     * firing into the next one and reaches for a session factory that is still being built. What
     * that looks like from outside is an application that never finishes starting.
     */
    void onStop(@Observes ShutdownEvent ignored) {
        if (reconcileTimer != null) {
            vertx.cancelTimer(reconcileTimer);
            reconcileTimer = null;
        }
    }

    /**
     * The chores changed hands: start or stop watching on the rules' behalf.
     *
     * <p>Immediately rather than at the next re-read, because this is the handover the whole lease
     * exists for — an instance that has just taken over should be watching within a moment, not
     * within a tick.
     */
    void onLeadership(@Observes LeadershipChanged change) {
        LOG.debugf(
                "Chores %s: %s watching on the rules' behalf",
                change.leader() ? "taken on" : "given up",
                change.leader() ? "starting" : "stopping");
        reconcile();
    }

    /**
     * Reads the rules and makes what is watched match them.
     *
     * <p>Public because it is the honest way to put this back in step with the table after
     * something else has written to it — a test that empties the rules, and one day an instance
     * that is told to reload.
     */
    public Uni<Integer> reload() {
        return repository
                .enabled()
                .map(
                        rules -> {
                            rules.forEach(registry::register);
                            registry.retainOnly(
                                    rules.stream()
                                            .map(rule -> rule.id)
                                            .collect(Collectors.toSet()));
                            reconcile();
                            LOG.debugf(
                                    "Watching %d alert rules across %d targets",
                                    rules.size(), registry.connections().size());
                            return rules.size();
                        });
    }

    /** Takes a rule as it now is, and adjusts what is being sampled. */
    public void follow(AlertRule rule) {
        registry.register(rule);
        // The window may have just moved, and a figure taken over the old one would be
        // compared against for the next five minutes without anybody being able to see why.
        baselines.forget(rule.id);
        reconcile();
    }

    public void forget(Long ruleId) {
        registry.unregister(ruleId);
        baselines.forget(ruleId);
        reconcile();
    }

    /**
     * Makes what is being watched match what the rules need.
     *
     * <p>Reconciled as a set rather than adjusted per change, because an edit can move a rule from
     * one target to another and the interesting part of that is the target it left.
     */
    private void reconcile() {
        Set<Long> needed = leadership.isLeader() ? registry.connections() : Set.of();
        needed.forEach(
                connectionId -> {
                    if (holding.add(connectionId)) {
                        hold(connectionId);
                    }
                });
        List.copyOf(holding).stream()
                .filter(connectionId -> !needed.contains(connectionId))
                .forEach(
                        connectionId -> {
                            holding.remove(connectionId);
                            sampler.stop(connectionId, MetricsSampler.Reason.RULE);
                        });
    }

    private void hold(Long connectionId) {
        sampler.start(connectionId, MetricsSampler.Reason.RULE)
                .subscribe()
                .with(
                        ignored ->
                                LOG.debugf("Sampling %d because a rule watches it", connectionId),
                        // start() keeps the timer for a rule even when the first reading fails,
                        // so anything arriving here is unexpected rather than "the target is
                        // down" — which is a condition the rules themselves report.
                        failure ->
                                LOG.warnf(
                                        failure,
                                        "Could not start sampling connection %d for its rules",
                                        connectionId));
    }
}
