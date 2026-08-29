package io.keydra.alerts.persistence;

import io.keydra.alerts.entity.AlertDelivery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Reads and writes the places alerts are sent. */
@ApplicationScoped
public class AlertDeliveryRepository {

    public Uni<List<AlertDelivery>> all() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AlertDelivery order by name",
                                                AlertDelivery.class)
                                        .getResultList());
    }

    public Uni<AlertDelivery> byId(Long id) {
        return Panache.getSession().flatMap(session -> session.find(AlertDelivery.class, id));
    }

    /**
     * One delivery, read in a session of its own.
     *
     * <p>Its own because sending must not happen inside one: a webhook that hangs would otherwise
     * hold a database connection for as long as the far end takes to answer, and the far end is
     * somebody else's server.
     */
    @WithSession
    public Uni<AlertDelivery> forUse(Long id) {
        return byId(id);
    }

    public Uni<AlertDelivery> byName(String name) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AlertDelivery where name = :name",
                                                AlertDelivery.class)
                                        .setParameter("name", name)
                                        .getSingleResultOrNull());
    }

    public Uni<AlertDelivery> save(AlertDelivery delivery) {
        return Panache.getSession()
                .flatMap(session -> session.persist(delivery).replaceWith(delivery));
    }

    public Uni<Boolean> delete(Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from AlertDelivery where id = :id")
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(deleted -> deleted > 0);
    }
}
