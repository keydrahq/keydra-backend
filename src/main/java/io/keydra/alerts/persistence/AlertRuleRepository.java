package io.keydra.alerts.persistence;

import io.keydra.alerts.entity.AlertRule;
import io.keydra.alerts.entity.AlertRuleDelivery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads and writes the conditions somebody wants to hear about. */
@ApplicationScoped
public class AlertRuleRepository {

    public Uni<List<AlertRule>> all() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("from AlertRule order by name", AlertRule.class)
                                        .getResultList())
                .flatMap(this::withDeliveries);
    }

    /**
     * The rules that are actually watching, read in a session of its own.
     *
     * <p>Its own because this is what startup calls: there is no request around it, and the answer
     * decides which targets get sampled from then on.
     */
    @WithSession
    public Uni<List<AlertRule>> enabled() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AlertRule where enabled = true",
                                                AlertRule.class)
                                        .getResultList())
                .flatMap(this::withDeliveries);
    }

    public Uni<AlertRule> byId(Long id) {
        return Panache.getSession()
                .flatMap(session -> session.find(AlertRule.class, id))
                .flatMap(this::withDeliveries);
    }

    /** How many rules point at one delivery, which decides whether it can be removed. */
    public Uni<Long> usingDelivery(Long deliveryId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(*) from AlertRuleDelivery where"
                                                        + " deliveryId = :id",
                                                Long.class)
                                        .setParameter("id", deliveryId)
                                        .getSingleResult());
    }

    // --- Where a rule announces itself --------------------------------------

    /**
     * Fills in the destinations a rule was loaded without.
     *
     * <p>Here rather than as a mapped collection, which would be a lazy load on a reactive session
     * that every caller of this repository would have to know not to touch outside one. Filling it
     * in on the way out means the rule is complete wherever it arrives — including in the registry,
     * which holds it in memory long after the session is gone.
     */
    private Uni<AlertRule> withDeliveries(AlertRule rule) {
        if (rule == null) {
            return Uni.createFrom().nullItem();
        }
        return deliveriesFor(rule.id).invoke(found -> rule.deliveryIds = found).replaceWith(rule);
    }

    /** The same for a whole page of them, in one query rather than one per row. */
    private Uni<List<AlertRule>> withDeliveries(List<AlertRule> rules) {
        if (rules.isEmpty()) {
            return Uni.createFrom().item(rules);
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AlertRuleDelivery where ruleId in :ids",
                                                AlertRuleDelivery.class)
                                        .setParameter(
                                                "ids", rules.stream().map(rule -> rule.id).toList())
                                        .getResultList())
                .map(
                        rows -> {
                            Map<Long, List<Long>> byRule =
                                    rows.stream()
                                            .collect(
                                                    Collectors.groupingBy(
                                                            row -> row.ruleId,
                                                            Collectors.mapping(
                                                                    row -> row.deliveryId,
                                                                    Collectors.toList())));
                            for (AlertRule rule : rules) {
                                rule.deliveryIds = byRule.getOrDefault(rule.id, List.of());
                            }
                            return rules;
                        });
    }

    public Uni<List<Long>> deliveriesFor(Long ruleId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select deliveryId from AlertRuleDelivery where"
                                                        + " ruleId = :id order by deliveryId",
                                                Long.class)
                                        .setParameter("id", ruleId)
                                        .getResultList());
    }

    /**
     * Writes the list a rule was saved with, replacing whatever was there.
     *
     * <p>Wholesale, because that is how it arrives: the form sends the list it wants and there is
     * no endpoint that adds or removes one.
     */
    @WithTransaction
    public Uni<Void> replaceDeliveries(Long ruleId, List<Long> deliveryIds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from AlertRuleDelivery where ruleId = :id")
                                        .setParameter("id", ruleId)
                                        .executeUpdate()
                                        .flatMap(
                                                ignored -> {
                                                    Uni<Void> chain = Uni.createFrom().voidItem();
                                                    for (Long deliveryId :
                                                            deliveryIds.stream()
                                                                    .distinct()
                                                                    .toList()) {
                                                        AlertRuleDelivery row =
                                                                new AlertRuleDelivery();
                                                        row.ruleId = ruleId;
                                                        row.deliveryId = deliveryId;
                                                        chain =
                                                                chain.flatMap(
                                                                        done ->
                                                                                session.persist(
                                                                                        row));
                                                    }
                                                    return chain;
                                                }));
    }

    public Uni<AlertRule> save(AlertRule rule) {
        return Panache.getSession().flatMap(session -> session.persist(rule).replaceWith(rule));
    }

    public Uni<Boolean> delete(Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from AlertRule where id = :id")
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(deleted -> deleted > 0);
    }
}
