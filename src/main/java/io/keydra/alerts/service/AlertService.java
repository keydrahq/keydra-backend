package io.keydra.alerts.service;

import io.keydra.alerts.dto.AlertDtos.AlertEventSummary;
import io.keydra.alerts.dto.AlertDtos.AlertRuleRequest;
import io.keydra.alerts.dto.AlertDtos.AlertRuleSummary;
import io.keydra.alerts.entity.AlertBasis;
import io.keydra.alerts.entity.AlertEvent;
import io.keydra.alerts.entity.AlertRule;
import io.keydra.alerts.entity.Comparison;
import io.keydra.alerts.exception.AlertRefusedException;
import io.keydra.alerts.persistence.AlertDeliveryRepository;
import io.keydra.alerts.persistence.AlertEventRepository;
import io.keydra.alerts.persistence.AlertRuleRepository;
import io.keydra.alerts.service.AlertRegistry.Live;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.connections.persistence.ConnectionProfileRepository;
import io.keydra.monitoring.service.MetricsHistoryService;
import io.keydra.monitoring.service.MetricsSampler;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writing and changing the conditions somebody wants to hear about.
 *
 * <p>A rule is about one target, so who may see it is decided the same way the catalog decides it:
 * a rule about a server somebody cannot reach is none of their business, and filtering the list is
 * a better answer than a permission that would have to be granted separately from the target.
 *
 * <p>Every write goes on to {@link AlertWatches}, which is what makes a rule mean anything: writing
 * one is how somebody says a target should be watched even when nobody is looking at it.
 */
@ApplicationScoped
public class AlertService {

    /** How many events a history shows. Enough to see a night, not enough to be a table. */
    private static final int HISTORY = 200;

    /** An hour, where nobody said. Long enough that one busy minute is not the whole comparison. */
    private static final int DEFAULT_BASELINE_WINDOW = 3600;

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final AlertDeliveryRepository deliveries;
    private final ConnectionProfileRepository connections;
    private final AlertRegistry registry;
    private final Baselines baselines;
    private final MetricsHistoryService history;
    private final AlertWatches watches;
    private final MetricsSampler sampler;
    private final CallerPermissions caller;
    private final SecurityIdentity identity;

    @Inject
    AlertService(
            AlertRuleRepository rules,
            AlertEventRepository events,
            AlertDeliveryRepository deliveries,
            ConnectionProfileRepository connections,
            AlertRegistry registry,
            Baselines baselines,
            MetricsHistoryService history,
            AlertWatches watches,
            MetricsSampler sampler,
            CallerPermissions caller,
            SecurityIdentity identity) {
        this.rules = rules;
        this.events = events;
        this.deliveries = deliveries;
        this.connections = connections;
        this.registry = registry;
        this.baselines = baselines;
        this.history = history;
        this.watches = watches;
        this.sampler = sampler;
        this.caller = caller;
        this.identity = identity;
    }

    /** Every rule the caller can see, with where each one stands. */
    @WithSession
    public Uni<List<AlertRuleSummary>> list() {
        return rules.all().flatMap(this::onlyVisible).flatMap(this::describe);
    }

    /** What the rules the caller can see have said. Filtered the same way the list is. */
    @WithSession
    public Uni<List<AlertEventSummary>> history(Long ruleId) {
        return events.recent(ruleId, HISTORY)
                .flatMap(
                        found ->
                                caller.visible(
                                                found.stream()
                                                        .map(event -> event.connectionId)
                                                        .distinct()
                                                        .toList())
                                        .flatMap(
                                                visible ->
                                                        describeEvents(
                                                                found.stream()
                                                                        .filter(
                                                                                event ->
                                                                                        visible
                                                                                                .contains(
                                                                                                        event.connectionId))
                                                                        .toList())));
    }

    @WithTransaction
    public Uni<AlertRuleSummary> create(AlertRuleRequest request) {
        AlertRule rule = new AlertRule();
        apply(rule, request);
        rule.createdBy = identity.isAnonymous() ? null : identity.getPrincipal().getName();
        return check(rule)
                .flatMap(ignored -> rules.save(rule))
                .call(saved -> rules.replaceDeliveries(saved.id, saved.deliveryIds))
                .invoke(watches::follow)
                .flatMap(saved -> describe(List.of(saved)))
                .map(described -> described.get(0));
    }

    @WithTransaction
    public Uni<AlertRuleSummary> update(Long id, AlertRuleRequest request) {
        return rules.byId(id)
                .flatMap(
                        rule -> {
                            if (rule == null) {
                                return Uni.createFrom()
                                        .failure(new AlertRefusedException("No such rule"));
                            }
                            apply(rule, request);
                            return check(rule)
                                    .call(
                                            ignored ->
                                                    rules.replaceDeliveries(
                                                            rule.id, rule.deliveryIds))
                                    .invoke(ignored -> watches.follow(rule))
                                    .flatMap(ignored -> describe(List.of(rule)))
                                    .map(described -> described.get(0));
                        });
    }

    /** Removes a rule and everything it ever said; a history without its rule answers nothing. */
    @WithTransaction
    public Uni<Boolean> delete(Long id) {
        return events.deleteFor(id)
                .flatMap(ignored -> rules.delete(id))
                .invoke(ignored -> watches.forget(id));
    }

    /** Which target a rule is about, so an endpoint can ask permission about the right one. */
    @WithSession
    public Uni<Long> connectionOf(Long ruleId) {
        return rules.byId(ruleId).map(rule -> rule == null ? null : rule.connectionId);
    }

    // --- The rules of a rule -----------------------------------------------

    private static void apply(AlertRule rule, AlertRuleRequest request) {
        rule.name = request.name();
        rule.connectionId = request.connectionId();
        rule.metric = request.metric();
        rule.comparison = request.comparison() == null ? Comparison.ABOVE : request.comparison();
        rule.basis = request.basis() == null ? AlertBasis.ABSOLUTE : request.basis();
        rule.threshold = request.threshold() == null ? 0 : request.threshold();
        rule.baselineWindowSeconds =
                request.baselineWindowSeconds() == null
                        ? DEFAULT_BASELINE_WINDOW
                        : request.baselineWindowSeconds();
        rule.baselineOffsetSeconds =
                request.baselineOffsetSeconds() == null ? 0 : request.baselineOffsetSeconds();
        rule.forSeconds = request.forSeconds() == null ? 0 : request.forSeconds();
        rule.enabled = request.enabled() == null || request.enabled();
        rule.deliveryIds =
                request.deliveryIds() == null
                        ? List.of()
                        : request.deliveryIds().stream()
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList();

        if (rule.metric != null && rule.metric.isCondition()) {
            // A yes-or-no condition has nothing to compare against. Storing whatever number
            // arrived would leave a rule whose displayed threshold means nothing, and whose
            // behaviour would change if somebody "corrected" it.
            rule.threshold = 0;
            rule.comparison = Comparison.ABOVE;
            // The basis is deliberately left as it arrived, to be refused rather than
            // corrected: quietly turning "forty per cent more silence than last week" into an
            // absolute rule would save perfectly and behave like something else.
        }
    }

    /**
     * Refuses a rule that cannot work, while whoever wrote it is still looking at it.
     *
     * <p>The target has to exist, because a rule about nothing watches nothing; the delivery has to
     * exist, because the failure of that one would only be discovered by the alert that needed it.
     */
    private Uni<Void> check(AlertRule rule) {
        if (rule.forSeconds < 0) {
            return refuse("A rule cannot wait for less than no time");
        }
        String impossible = whyItCannotComparteWithThePast(rule);
        if (impossible != null) {
            return refuse(impossible);
        }
        return connections
                .findById(rule.connectionId)
                .flatMap(
                        profile -> {
                            if (profile == null) {
                                return refuse("No such target");
                            }
                            if (rule.deliveryIds.isEmpty()) {
                                return Uni.createFrom().voidItem();
                            }
                            // One after another: a reactive session runs one query at a time,
                            // and a rule names two or three destinations rather than twenty.
                            Uni<Void> chain = Uni.createFrom().voidItem();
                            for (Long deliveryId : rule.deliveryIds) {
                                chain =
                                        chain.flatMap(
                                                ignored ->
                                                        deliveries
                                                                .byId(deliveryId)
                                                                .flatMap(
                                                                        delivery ->
                                                                                delivery == null
                                                                                        ? refuse(
                                                                                                "A delivery"
                                                                                                    + " this"
                                                                                                    + " rule"
                                                                                                    + " points"
                                                                                                    + " at does"
                                                                                                    + " not exist")
                                                                                        : Uni
                                                                                                .createFrom()
                                                                                                .voidItem()));
                            }
                            return chain;
                        });
    }

    /**
     * Why a rule cannot be written against the past, or null when it can.
     *
     * <p>Answered while somebody is still looking at the form. Each of these would otherwise be a
     * rule that saves perfectly and then never fires, which is the worst way for an alert to be
     * wrong: silence looks exactly like nothing being wrong.
     */
    private String whyItCannotComparteWithThePast(AlertRule rule) {
        if (rule.basis != AlertBasis.BASELINE) {
            return null;
        }
        if (rule.metric.isCondition()) {
            return "A yes-or-no condition has nothing to be a percentage of";
        }
        if (rule.metric.needsTwoReadings()) {
            return "A rate measured between two readings cannot be averaged over a window; write"
                    + " this one against a number";
        }
        if (rule.baselineWindowSeconds <= 0) {
            return "A window to compare against has to be longer than no time";
        }
        if (rule.baselineOffsetSeconds < 0) {
            return "A window to compare against cannot be in the future";
        }
        if (rule.threshold <= 0) {
            return "A share of the baseline has to be more than nothing";
        }
        long reach = rule.baselineWindowSeconds + (long) rule.baselineOffsetSeconds;
        if (!history.durable() && reach > history.memoryReach().toSeconds()) {
            return "Keydra keeps about "
                    + history.memoryReach().toMinutes()
                    + " minutes of readings in memory; comparing with anything older needs the"
                    + " readings store";
        }
        return null;
    }

    private static Uni<Void> refuse(String why) {
        return Uni.createFrom().failure(new AlertRefusedException(why));
    }

    // --- Describing --------------------------------------------------------

    private Uni<List<AlertRule>> onlyVisible(List<AlertRule> found) {
        return caller.visible(found.stream().map(rule -> rule.connectionId).distinct().toList())
                .map(
                        visible ->
                                found.stream()
                                        .filter(rule -> visible.contains(rule.connectionId))
                                        .toList());
    }

    private Uni<List<AlertRuleSummary>> describe(List<AlertRule> found) {
        return names().flatMap(
                        names ->
                                deliveryNames()
                                        .map(
                                                sending ->
                                                        found.stream()
                                                                .map(
                                                                        rule ->
                                                                                toSummary(
                                                                                        rule, names,
                                                                                        sending))
                                                                .toList()));
    }

    private AlertRuleSummary toSummary(
            AlertRule rule, Map<Long, String> names, Map<Long, String> sending) {
        Live live = registry.live(rule.id);
        return new AlertRuleSummary(
                rule.id,
                rule.name,
                rule.connectionId,
                names.getOrDefault(rule.connectionId, "?"),
                rule.metric,
                rule.metric.unit(),
                rule.comparison,
                rule.basis,
                rule.threshold,
                rule.baselineWindowSeconds,
                rule.baselineOffsetSeconds,
                baselines.of(AlertRegistry.Watched.of(rule)),
                rule.forSeconds,
                rule.enabled,
                rule.deliveryIds,
                rule.deliveryIds.stream()
                        .map(id -> sending.getOrDefault(id, String.valueOf(id)))
                        .toList(),
                rule.createdBy,
                rule.createdAt,
                live.state(),
                live.since(),
                live.reading(),
                live.readAt(),
                sampler.state(rule.connectionId).enabled());
    }

    private Uni<List<AlertEventSummary>> describeEvents(List<AlertEvent> found) {
        return names().map(
                        names ->
                                found.stream()
                                        .map(
                                                event ->
                                                        new AlertEventSummary(
                                                                event.id,
                                                                event.ruleId,
                                                                event.ruleName,
                                                                event.connectionId,
                                                                names.getOrDefault(
                                                                        event.connectionId, "?"),
                                                                event.kind,
                                                                event.metric,
                                                                event.reading,
                                                                event.threshold,
                                                                event.at,
                                                                event.deliveryName,
                                                                event.deliveryOutcome,
                                                                event.deliveryDetail))
                                        .toList());
    }

    private Uni<Map<Long, String>> names() {
        return connections
                .listAll()
                .map(
                        profiles -> {
                            Map<Long, String> names = new HashMap<>();
                            profiles.forEach(profile -> names.put(profile.id, profile.name));
                            return names;
                        });
    }

    private Uni<Map<Long, String>> deliveryNames() {
        return deliveries
                .all()
                .map(
                        found -> {
                            Map<Long, String> names = new HashMap<>();
                            found.forEach(delivery -> names.put(delivery.id, delivery.name));
                            return names;
                        });
    }
}
