package io.keydra.alerts.service;

import io.keydra.alerts.dto.AlertNotice;
import io.keydra.alerts.entity.AlertEvent;
import io.keydra.alerts.entity.AlertState;
import io.keydra.alerts.entity.DeliveryOutcome;
import io.keydra.alerts.entity.EventKind;
import io.keydra.alerts.persistence.AlertEventRepository;
import io.keydra.alerts.service.AlertRegistry.Live;
import io.keydra.alerts.service.AlertRegistry.Watched;
import io.keydra.cluster.service.Leadership;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.MetricsSample;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.telemetry.service.KeydraMeters;
import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Decides, on every reading, whether anybody needs to be told.
 *
 * <p>Called by the sampler rather than running on a clock of its own. A second timer would mean two
 * ideas of what "now" is and a rule evaluated against a reading it has already seen; this way a
 * rule is considered exactly once per reading, which is also why watching costs the target nothing
 * beyond the sampling it was already doing.
 *
 * <p>Three states and two transitions. A rule whose condition has held all night sent one message
 * when it started and will send one more when it stops — a design decision and not an omission,
 * because the alternative is a channel full of the same line that everybody mutes by morning.
 *
 * <p>Decided by the instance holding the chores and by no other. A reading taken on a second
 * instance — somebody has a dashboard open there — is not evaluated at all: two processes deciding
 * the same rule would send two of every message, and the states they each kept would be two
 * opinions about one server. Where a rule stands is therefore a fact about the instance that
 * decides it, which is the same instance that samples on its behalf.
 */
@ApplicationScoped
public class AlertEvaluator {

    private static final Logger LOG = Logger.getLogger(AlertEvaluator.class);

    private final AlertRegistry registry;
    private final Baselines baselines;
    private final AlertEventRepository events;
    private final AlertSender sender;
    private final ConnectionService connections;
    private final NotificationHub hub;
    private final Leadership leadership;
    private final KeydraMeters meters;
    private final Vertx vertx;

    @Inject
    AlertEvaluator(
            AlertRegistry registry,
            Baselines baselines,
            AlertEventRepository events,
            AlertSender sender,
            ConnectionService connections,
            NotificationHub hub,
            Leadership leadership,
            KeydraMeters meters,
            Vertx vertx) {
        this.registry = registry;
        this.baselines = baselines;
        this.events = events;
        this.sender = sender;
        this.connections = connections;
        this.hub = hub;
        this.leadership = leadership;
        this.meters = meters;
        this.vertx = vertx;
    }

    /** A fresh reading arrived for a target: consider every rule watching it. */
    public void onReading(Long connectionId, MetricsSample reading, MetricsSample previous) {
        consider(connectionId, reading, previous, reading == null ? Instant.now() : reading.at());
    }

    /**
     * The target said nothing.
     *
     * <p>Not the same as no news. Every rule about a quantity is left exactly as it was, because
     * the absence of a reading is not a value to compare; the one rule whose subject is the silence
     * treats it as its condition holding.
     */
    public void onSilence(Long connectionId) {
        consider(connectionId, null, null, Instant.now());
    }

    private void consider(
            Long connectionId, MetricsSample reading, MetricsSample previous, Instant at) {
        if (!leadership.isLeader()) {
            return;
        }
        for (Watched rule : registry.forConnection(connectionId)) {
            try {
                evaluate(rule, reading, previous, at);
            } catch (RuntimeException failure) {
                // One rule that cannot be evaluated must not stop the others, which are about
                // the same server and may be the ones that matter.
                LOG.errorf(failure, "Could not evaluate alert rule %d", rule.id());
            }
        }
    }

    private void evaluate(Watched rule, MetricsSample now, MetricsSample before, Instant at) {
        Double value = rule.metric().of(now, before);
        Live current = registry.live(rule.id());

        if (value == null) {
            // Nothing to compare. The state stands and the interface says the reading is
            // missing, which is the truth rather than a zero somebody would act on.
            registry.update(rule.id(), current.seeing(null, at));
            return;
        }

        Double against = threshold(rule);
        if (against == null) {
            // A rule written against the past, with nothing to compare against yet: no store,
            // a window older than anything kept, or a target nobody was watching then. The
            // reading is recorded so the interface can show it, and the state stands — the
            // same answer a missing reading gets, for the same reason.
            registry.update(rule.id(), current.seeing(value, at));
            return;
        }

        boolean breached = rule.comparison().holds(value, against);

        if (!breached) {
            if (current.state() == AlertState.FIRING) {
                registry.update(rule.id(), new Live(AlertState.OK, at, value, at));
                raise(rule, EventKind.CLEARED, value, against, at);
            } else {
                registry.update(rule.id(), new Live(AlertState.OK, at, value, at));
            }
            return;
        }

        switch (current.state()) {
            case OK -> {
                registry.update(rule.id(), new Live(AlertState.PENDING, at, value, at));
                if (rule.forSeconds() <= 0) {
                    fire(rule, value, against, at);
                }
            }
            case PENDING -> {
                boolean longEnough =
                        current.since() != null
                                && Duration.between(current.since(), at).getSeconds()
                                        >= rule.forSeconds();
                if (longEnough) {
                    fire(rule, value, against, at);
                } else {
                    registry.update(rule.id(), current.seeing(value, at));
                }
            }
            case FIRING -> registry.update(rule.id(), current.seeing(value, at));
        }
    }

    /**
     * The number this reading is actually measured against.
     *
     * <p>The rule's own for an absolute one, and a share of the baseline for a rule written against
     * the past: a hundred and forty per cent of what the metric read over that window. Null when
     * there is no baseline yet, which the caller treats as "nothing to compare".
     */
    private Double threshold(Watched rule) {
        if (!rule.comparesWithThePast()) {
            return rule.threshold();
        }
        Double baseline = baselines.of(rule);
        return baseline == null ? null : baseline * rule.threshold() / 100.0;
    }

    private void fire(Watched rule, Double value, Double against, Instant at) {
        registry.update(rule.id(), new Live(AlertState.FIRING, at, value, at));
        raise(rule, EventKind.FIRED, value, against, at);
    }

    /**
     * Writes it down, says it out loud, and then tries to send it.
     *
     * <p>In that order, and the order is the point. The event is committed before anything is sent
     * and the hub is told before anybody waits on a webhook, so an alert whose delivery hangs is
     * still an alert that happened — visible in the application and in the history while the far
     * end is still deciding whether to answer.
     */
    private void raise(Watched rule, EventKind kind, Double value, Double against, Instant at) {
        onACleanContext(rule.id(), () -> announceAndRecord(rule, kind, value, against, at));
    }

    /**
     * Runs the writing on a context of its own.
     *
     * <p>Not a flourish. A rule can be evaluated from a reading taken during the request that
     * created it, and that request's Hibernate session is closed the moment it answers — while this
     * work is still going. Joining it produced exactly what it sounds like: "Session/EntityManager
     * is closed", on the one write that was the whole point, with the alert already announced.
     *
     * <p>A fresh duplicated context rather than a duplicate of the current one, so nothing is
     * inherited: what is wanted here is a session of this work's own, whoever happened to call it.
     */
    private void onACleanContext(Long ruleId, Supplier<Uni<Void>> work) {
        Context current = Vertx.currentContext();
        Context own =
                current == null
                        ? VertxContext.getOrCreateDuplicatedContext(vertx)
                        : VertxContext.createNewDuplicatedContext(current);
        VertxContextSafetyToggle.setContextSafe(own, true);
        own.runOnContext(
                ignored ->
                        work.get()
                                .subscribe()
                                .with(
                                        done -> {},
                                        failure ->
                                                LOG.errorf(
                                                        failure,
                                                        "Could not write down what rule %d did",
                                                        ruleId)));
    }

    private Uni<Void> announceAndRecord(
            Watched rule, EventKind kind, Double value, Double against, Instant at) {
        return nameOf(rule.connectionId())
                .map(
                        connectionName ->
                                new AlertNotice(
                                        rule.id(),
                                        rule.name(),
                                        rule.connectionId(),
                                        connectionName,
                                        // A rule is about a target, whose own name is the whole
                                        // answer.
                                        null,
                                        kind,
                                        rule.metric(),
                                        rule.comparison(),
                                        rule.metric().isCondition() ? null : value,
                                        // What it was actually measured against, which for a
                                        // rule written against the past is a share of what
                                        // the metric read then. A history that recorded "140"
                                        // would be a history of the setting rather than of
                                        // what happened.
                                        against,
                                        at))
                .invoke(
                        notice -> {
                            meters.alertRaised(notice.kind().name());
                            hub.broadcast(
                                    NotificationCategory.ALERT_CHANGED,
                                    notice.connectionId(),
                                    notice);
                        })
                .flatMap(
                        notice ->
                                events.record(newEvent(rule, notice, against))
                                        .flatMap(event -> deliver(rule, notice, event)))
                .invoke(() -> LOG.debugf("Alert %s for rule %d", kind, rule.id()))
                .onFailure()
                .invoke(
                        failure ->
                                LOG.errorf(
                                        failure,
                                        "Could not record that rule %d %s",
                                        rule.id(),
                                        kind))
                .onFailure()
                .recoverWithNull()
                .replaceWithVoid();
    }

    private AlertEvent newEvent(Watched rule, AlertNotice notice, Double against) {
        AlertEvent event = new AlertEvent();
        event.ruleId = rule.id();
        event.ruleName = rule.name();
        event.connectionId = rule.connectionId();
        event.kind = notice.kind();
        event.metric = rule.metric();
        event.reading = notice.reading();
        event.threshold = against == null ? rule.threshold() : against;
        event.at = notice.at();
        event.deliveryOutcome =
                rule.deliveryIds().isEmpty() ? DeliveryOutcome.NONE : DeliveryOutcome.SENDING;
        return event;
    }

    /**
     * Tells everywhere the rule names, and records the worst of what happened.
     *
     * <p>One after another rather than at once. Three destinations together is three outbound
     * requests from the instance holding the chores at the moment something is already wrong, and
     * the ordering costs a few hundred milliseconds on a path where nothing is waiting on the
     * answer. A destination that fails does not stop the others: the whole point of naming three is
     * that one of them being down is the case this exists for.
     */
    private Uni<Void> deliver(Watched rule, AlertNotice notice, AlertEvent event) {
        if (rule.deliveryIds().isEmpty()) {
            meters.alertDelivered(DeliveryOutcome.NONE.name());
            return Uni.createFrom().voidItem();
        }
        // A supplier rather than a made list: item(T) hands every subscription the same instance,
        // and a chain that was ever subscribed twice would report the first attempt's outcomes
        // alongside the second's.
        Uni<List<AlertSender.Sent>> chain =
                Uni.createFrom().item(() -> new ArrayList<AlertSender.Sent>());
        for (Long deliveryId : rule.deliveryIds()) {
            chain =
                    chain.flatMap(
                            sofar ->
                                    sender.send(deliveryId, notice)
                                            .invoke(
                                                    sent ->
                                                            meters.alertDelivered(
                                                                    sent.outcome().name()))
                                            .map(
                                                    sent -> {
                                                        sofar.add(sent);
                                                        return sofar;
                                                    }));
        }
        return chain.flatMap(
                sent -> events.delivered(event.id, namesOf(sent), worstOf(sent), detailOf(sent)));
    }

    /**
     * The worst of what happened, not the best.
     *
     * <p>Two of three delivered means somebody's channel did not hear, and an event that said
     * "sent" would be lying to exactly the person who needs to know it did not arrive.
     */
    private static DeliveryOutcome worstOf(List<AlertSender.Sent> sent) {
        return sent.stream().anyMatch(one -> one.outcome() == DeliveryOutcome.FAILED)
                ? DeliveryOutcome.FAILED
                : DeliveryOutcome.SENT;
    }

    private static String namesOf(List<AlertSender.Sent> sent) {
        return sent.stream()
                .map(AlertSender.Sent::name)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * What to say about it, which is only ever about the ones that did not arrive.
     *
     * <p>"Not all of them" is not actionable; "the on-call inbox refused the sender" is. Where
     * everything arrived there is nothing to add, and the outcome already says so.
     */
    private static String detailOf(List<AlertSender.Sent> sent) {
        String failures =
                sent.stream()
                        .filter(one -> one.outcome() == DeliveryOutcome.FAILED)
                        .map(
                                one ->
                                        one.detail() == null
                                                ? one.name()
                                                : one.name() + ": " + one.detail())
                        .collect(java.util.stream.Collectors.joining("; "));
        return failures.isBlank() ? null : failures;
    }

    /**
     * What the target is called, or nothing when it cannot be read.
     *
     * <p>Recovered rather than propagated: a missing name is a worse message, and no message at all
     * is a worse outcome than a message that says "connection 4".
     */
    private Uni<String> nameOf(Long connectionId) {
        return connections
                .load(connectionId)
                .map(profile -> profile == null ? null : profile.name)
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(
                                    failure,
                                    "Could not read the name of connection %d",
                                    connectionId);
                            return null;
                        });
    }
}
