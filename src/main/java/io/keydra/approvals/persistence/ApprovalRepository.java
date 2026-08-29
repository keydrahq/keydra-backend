package io.keydra.approvals.persistence;

import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.entity.ApprovalState;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/** Reads and writes the operations that are waiting for somebody. */
@ApplicationScoped
public class ApprovalRepository {

    /**
     * The newest first, because a page about things waiting is read from the top.
     *
     * @param onlyOpen whether to leave out the ones that have already ended
     */
    @WithSession
    public Uni<List<ApprovalRequest>> all(boolean onlyOpen, int limit) {
        String query =
                onlyOpen
                        ? "from ApprovalRequest where state in ('PENDING', 'RUNNING') order by"
                                + " requestedAt desc"
                        : "from ApprovalRequest order by requestedAt desc";
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(query, ApprovalRequest.class)
                                        .setMaxResults(limit)
                                        .getResultList());
    }

    @WithSession
    public Uni<ApprovalRequest> byId(Long id) {
        return Panache.getSession().flatMap(session -> session.find(ApprovalRequest.class, id));
    }

    @WithTransaction
    public Uni<ApprovalRequest> save(ApprovalRequest request) {
        return Panache.getSession()
                .flatMap(session -> session.persist(request).replaceWith(request));
    }

    /**
     * Moves one out of {@code PENDING}, and says whether it was this caller who moved it.
     *
     * <p>The whole of the answer to two people pressing approve in the same second, and it is the
     * database's answer rather than the application's: the update names the state it expects to
     * find, so exactly one of them changes a row and the other is told the request has already been
     * answered. Anything read-then-write here would be a race with an irreversible operation on the
     * other side of it.
     */
    @WithTransaction
    public Uni<Boolean> claim(Long id, ApprovalState to, String by, Instant at, String detail) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update ApprovalRequest set state = :to, decidedBy"
                                                    + " = :by, decidedAt = :at, detail = :detail"
                                                    + " where id = :id and state = 'PENDING'")
                                        .setParameter("to", to)
                                        .setParameter("by", by)
                                        .setParameter("at", at)
                                        .setParameter("detail", detail)
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(changed -> changed > 0);
    }

    /** Records how a running request ended. */
    @WithTransaction
    public Uni<Void> finish(Long id, ApprovalState to, String detail) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update ApprovalRequest set state = :to, detail ="
                                                        + " :detail where id = :id")
                                        .setParameter("to", to)
                                        .setParameter("detail", detail)
                                        .setParameter("id", id)
                                        .executeUpdate())
                .replaceWithVoid();
    }

    /** Everything still pending whose time has run out. */
    @WithSession
    public Uni<List<ApprovalRequest>> lapsed(Instant now) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ApprovalRequest where state = 'PENDING' and"
                                                        + " expiresAt < :now",
                                                ApprovalRequest.class)
                                        .setParameter("now", now)
                                        .getResultList());
    }
}
