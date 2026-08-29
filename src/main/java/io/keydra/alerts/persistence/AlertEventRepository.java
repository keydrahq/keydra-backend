package io.keydra.alerts.persistence;

import io.keydra.alerts.entity.AlertEvent;
import io.keydra.alerts.entity.DeliveryOutcome;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * What the rules have said, and what became of saying it.
 *
 * <p>The write is split in two on purpose, the same way a scheduled run is: the event is committed
 * before anything is sent, and the outcome is committed after. Written in one transaction around
 * the sending, an alert whose webhook never answers would be an alert that never happened — and
 * "nothing fired" and "something fired and could not be delivered" are the two answers that must
 * never look alike.
 */
@ApplicationScoped
public class AlertEventRepository {

    /** Records that a rule changed its mind, and commits it before anything is sent. */
    @WithTransaction
    public Uni<AlertEvent> record(AlertEvent event) {
        return Panache.getSession().flatMap(session -> session.persist(event).replaceWith(event));
    }

    /**
     * Writes what became of the attempt to send it.
     *
     * <p>The name of the delivery is copied onto the event rather than referenced, so removing a
     * webhook does not erase the record of what it did.
     */
    @WithTransaction
    public Uni<Void> delivered(
            Long eventId, String deliveryName, DeliveryOutcome outcome, String detail) {
        return Panache.getSession()
                .flatMap(session -> session.find(AlertEvent.class, eventId))
                .invoke(
                        event -> {
                            if (event != null) {
                                event.deliveryName = deliveryName;
                                event.deliveryOutcome = outcome;
                                event.deliveryDetail = detail;
                            }
                        })
                .replaceWithVoid();
    }

    /** The newest events, for one rule or for the whole instance. */
    @WithSession
    public Uni<List<AlertEvent>> recent(Long ruleId, int limit) {
        String query =
                ruleId == null
                        ? "from AlertEvent order by at desc"
                        : "from AlertEvent where ruleId = :id order by at desc";
        return Panache.getSession()
                .flatMap(
                        session -> {
                            var typed =
                                    session.createQuery(query, AlertEvent.class)
                                            .setMaxResults(limit);
                            return ruleId == null
                                    ? typed.getResultList()
                                    : typed.setParameter("id", ruleId).getResultList();
                        });
    }

    /** Removes a rule's history, which is what deleting the rule does to it. */
    public Uni<Integer> deleteFor(Long ruleId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from AlertEvent where ruleId = :id")
                                        .setParameter("id", ruleId)
                                        .executeUpdate());
    }
}
