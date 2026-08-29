package io.keydra.authz.persistence;

import io.keydra.authz.entity.AccountInvitation;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/** The invitation rows, kept apart from the rest of authz because they answer one question. */
@ApplicationScoped
public class InvitationRepository {

    /**
     * The invitation a link names, whether or not it is still good for anything.
     *
     * <p>Looked up by fingerprint, which is the only form the database ever holds. An expired or
     * already-used row comes back rather than being filtered out: "this link has expired" and "no
     * such link" are different sentences, and somebody who followed an old mail deserves the first.
     */
    public Uni<AccountInvitation> byFingerprint(String fingerprint) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AccountInvitation where tokenHash ="
                                                        + " :fingerprint",
                                                AccountInvitation.class)
                                        .setParameter("fingerprint", fingerprint)
                                        .getSingleResultOrNull());
    }

    public Uni<AccountInvitation> save(AccountInvitation invitation) {
        return Panache.getSession()
                .flatMap(session -> session.persist(invitation).replaceWith(invitation));
    }

    /**
     * Ends every live invitation for an account.
     *
     * <p>Called before a new one is written, so a link somebody forwarded last week stops working
     * the moment a fresh one is asked for. Marked as accepted rather than deleted: the history of
     * how an account came to have a password is worth keeping, and "superseded" is what an
     * unaccepted row with a newer sibling means.
     */
    public Uni<Integer> endLiveFor(Long userId, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update AccountInvitation set acceptedAt = :at"
                                                    + " where userId = :userId and acceptedAt is"
                                                    + " null")
                                        .setParameter("at", at)
                                        .setParameter("userId", userId)
                                        .executeUpdate());
    }

    /** Records that a link has been used, which must happen exactly once. */
    public Uni<Integer> markAccepted(Long id, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update AccountInvitation set acceptedAt = :at"
                                                        + " where id = :id and acceptedAt is null")
                                        .setParameter("at", at)
                                        .setParameter("id", id)
                                        .executeUpdate());
    }
}
