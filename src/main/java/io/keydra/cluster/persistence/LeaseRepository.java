package io.keydra.cluster.persistence;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Taking, keeping and giving up the right to do a job that must only be done once.
 *
 * <p>One statement, and it has to be one: two instances asking at the same moment must not both be
 * told yes, and "read the row, decide, write it back" is the shape of that mistake. {@code INSERT
 * ... ON CONFLICT DO UPDATE ... WHERE} claims and renews in the same breath — the row is written
 * only when nobody else's lease is live, and how many rows that wrote is the answer.
 *
 * <p>Native SQL for the same reason: the decision is made by the database, in the database's own
 * clock, and there is no way to say that in HQL.
 */
@ApplicationScoped
public class LeaseRepository {

    /**
     * Claims a lease, or renews the one this holder already has.
     *
     * @return true when this holder now holds it, false when somebody else's is still live
     */
    @WithTransaction
    public Uni<Boolean> claim(String role, String holder, int seconds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(claimSql(seconds))
                                        .setParameter("role", role)
                                        .setParameter("holder", holder)
                                        .executeUpdate())
                .map(written -> written > 0);
    }

    /**
     * Gives the lease up, if this holder has it.
     *
     * <p>Only ever a courtesy: a lease nobody releases expires on its own, which is what makes a
     * crash no different from a shutdown. Doing it anyway means a rolling restart hands the work
     * over in a moment rather than in a lease.
     *
     * <p>Expired rather than deleted, which phase 61 needed and phase 17 had no use for. To {@link
     * #claim} the two are the same — the statement takes an expired row exactly as it takes a
     * missing one — but only one of them can answer "since when has nobody been doing the chores".
     * A fleet that drained cleanly used to leave nothing behind to put a date on.
     */
    @WithTransaction
    public Uni<Boolean> release(String role, String holder) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(
                                                "update instance_lease set expires_at = now() where"
                                                        + " role = :role and holder = :holder and"
                                                        + " expires_at > now()")
                                        .setParameter("role", role)
                                        .setParameter("holder", holder)
                                        .executeUpdate())
                .map(released -> released > 0);
    }

    /**
     * When the last live claim on this ran out, or null where nobody has ever made one.
     *
     * <p>Answers only while it is going spare: a lease somebody holds has an expiry in the future
     * and that is a schedule rather than a symptom. Null for an installation whose first beat has
     * not happened, which is not news — an instance that has just started claims one in a third of
     * a lease.
     */
    @WithSession
    public Uni<java.time.Instant> lapsedAt(String role) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(
                                                "select expires_at from instance_lease where role ="
                                                        + " :role and expires_at <= now()",
                                                java.time.Instant.class)
                                        .setParameter("role", role)
                                        .getSingleResultOrNull());
    }

    /** Who holds it at this moment, or null when it is going spare. For saying so, not deciding. */
    @WithSession
    public Uni<String> holder(String role) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(
                                                "select holder from instance_lease where role ="
                                                        + " :role and expires_at > now()",
                                                String.class)
                                        .setParameter("role", role)
                                        .getSingleResultOrNull());
    }

    /**
     * The one statement, with the length of the lease written into it.
     *
     * <p>Written in rather than bound: it is an interval literal, and how a driver ought to send
     * one is a question with several answers and no good one. The value is an {@code int} from
     * configuration, so there is nothing here for a string to smuggle in.
     */
    private static String claimSql(int seconds) {
        return "insert into instance_lease (role, holder, expires_at) values (:role, :holder, now()"
                + " + interval '"
                + Math.max(1, seconds)
                + " seconds') on conflict (role) do update set holder = excluded.holder, expires_at"
                + " = excluded.expires_at where instance_lease.holder = excluded.holder or"
                + " instance_lease.expires_at <= now()";
    }
}
