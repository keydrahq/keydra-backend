package io.keydra.cluster.persistence;

import io.keydra.cluster.entity.InstanceNoticeState;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * What has already been said about Keydra's own condition, and who gets to say the next thing.
 *
 * <p>Two statements rather than one, because the two edges are not symmetrical. Starting to say
 * something has to write a row that may not exist; stopping saying it must never write one, because
 * clearing something nobody was told about is a message that answers a question nobody asked.
 *
 * <p>Both name the state they expect to find, so two instances noticing the same thing in the same
 * second send one message between them. That is what lets this run without anybody being in charge
 * — which it has to, because what it watches for includes nobody being in charge.
 */
@ApplicationScoped
public class NoticeStateRepository {

    /**
     * Starts saying something about a subject, and says whether this caller is who started.
     *
     * <p>The upsert covers the first time anything is said at all. Its update is filtered on the
     * state changing, so an instance arriving second changes no row and is told so; its {@code
     * conflict} branch is the same race in its other form, two instances inserting at once.
     */
    @WithTransaction
    public Uni<Boolean> begin(String subject) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(
                                                """
                                                with moved as (
                                                    insert into instance_notice_state
                                                        (subject, firing, since)
                                                    values (:subject, true, now())
                                                    on conflict (subject) do update
                                                       set firing = true, since = now()
                                                     where instance_notice_state.firing = false
                                                    returning subject
                                                )
                                                select subject from moved
                                                """,
                                                String.class)
                                        .setParameter("subject", subject)
                                        .getSingleResultOrNull())
                .map(java.util.Objects::nonNull);
    }

    /**
     * Stops saying it, and says whether this caller is who stopped.
     *
     * <p>An update and never an insert. A subject nobody was told about has no row, and writing one
     * here would turn "everything is fine" into a message — which is the shape of alert everybody
     * learns to ignore.
     */
    @WithTransaction
    public Uni<Boolean> end(String subject) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(
                                                "update instance_notice_state set firing = false,"
                                                    + " since = now() where subject = :subject and"
                                                    + " firing = true")
                                        .setParameter("subject", subject)
                                        .executeUpdate())
                .map(changed -> changed > 0);
    }

    /** When the current state was entered, for anything that wants to say how long. */
    @WithSession
    public Uni<Instant> since(String subject) {
        return Panache.getSession()
                .flatMap(session -> session.find(InstanceNoticeState.class, subject))
                .map(state -> state == null ? null : state.since);
    }
}
