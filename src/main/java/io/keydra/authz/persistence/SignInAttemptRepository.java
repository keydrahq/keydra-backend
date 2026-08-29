package io.keydra.authz.persistence;

import io.keydra.authz.entity.SignInAttempt;
import io.keydra.authz.entity.SignInOutcome;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * The attempt rows: one per try at signing in.
 *
 * <p>Everything here is a count or a short list bounded by time. The table grows with traffic and
 * is swept on a retention window, so nothing reads it without a {@code since} — a query that walked
 * the whole history would get slower every week it ran.
 */
@ApplicationScoped
public class SignInAttemptRepository {

    public Uni<SignInAttempt> save(SignInAttempt attempt) {
        return Panache.getSession()
                .flatMap(session -> session.persist(attempt).replaceWith(attempt));
    }

    /**
     * The outcomes that count towards a limit.
     *
     * <p>Named rather than written as "anything that is not a success", which is the version that
     * has a trap in it: a refusal is not a success, so counting it would make every refused attempt
     * extend the window that caused it. Somebody could then hold a named account shut for as long
     * as they cared to keep knocking — a limit meant to stop guessing, turned into a way of
     * stopping one person working. Only a password that was actually checked and found wrong
     * counts, which is the only thing guessing produces.
     */
    private static final List<SignInOutcome> COUNTED =
            List.of(SignInOutcome.WRONG_PASSWORD, SignInOutcome.NO_SUCH_ACCOUNT);

    /** Wrong passwords for one name since a moment. The username half of the throttle. */
    public Uni<Long> failuresForUsernameSince(String username, Instant since) {
        return count(
                "select count(a) from SignInAttempt a where a.username = :username and a.outcome in"
                        + " :counted and a.at >= :since",
                "username",
                username,
                since);
    }

    /** Wrong passwords from one network since a moment. The half that sees a spray of names. */
    public Uni<Long> failuresForNetworkSince(String network, Instant since) {
        return count(
                "select count(a) from SignInAttempt a where a.network = :network and a.outcome in"
                        + " :counted and a.at >= :since",
                "network",
                network,
                since);
    }

    /** Everything from one network since a moment, however it ended. */
    public Uni<Long> attemptsFromNetworkSince(String network, Instant since) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(a) from SignInAttempt a where"
                                                    + " a.network = :network and a.at >= :since",
                                                Long.class)
                                        .setParameter("network", network)
                                        .setParameter("since", since)
                                        .getSingleResult());
    }

    /** How many different names one network has signed into since a moment. */
    public Uni<Long> accountsReachedFromNetworkSince(String network, Instant since) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(distinct a.username) from"
                                                    + " SignInAttempt a where a.network = :network"
                                                    + " and a.outcome = :succeeded and a.at >="
                                                    + " :since",
                                                Long.class)
                                        .setParameter("network", network)
                                        .setParameter("succeeded", SignInOutcome.SUCCEEDED)
                                        .setParameter("since", since)
                                        .getSingleResult());
    }

    /**
     * Whether this name has ever succeeded with this value in this column.
     *
     * <p>The column is named by the caller from a closed set of three constants below, never from
     * anything a request carried — Hibernate has no way to parameterise a property name, and the
     * only safe version of that is one where the possibilities are written down here.
     */
    public Uni<Boolean> hasSucceededWith(String username, Column column, String value) {
        if (value == null) {
            // Nothing to compare against is not the same as never having seen it. An address the
            // proxy did not forward would otherwise make every sign-in look like a new one.
            return Uni.createFrom().item(true);
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(a) from SignInAttempt a where"
                                                        + " a.username = :username and a.outcome ="
                                                        + " :succeeded and a."
                                                        + column.property
                                                        + " = :value",
                                                Long.class)
                                        .setParameter("username", username)
                                        .setParameter("succeeded", SignInOutcome.SUCCEEDED)
                                        .setParameter("value", value)
                                        .getSingleResult())
                .map(seen -> seen > 0);
    }

    /** The columns {@link #hasSucceededWith} will compare on, and the only ones. */
    public enum Column {
        NETWORK("network"),
        COUNTRY("country"),
        USER_AGENT("userAgent");

        private final String property;

        Column(String property) {
            this.property = property;
        }
    }

    /** The most recent sign-in that worked for this name, not counting the one being written. */
    public Uni<SignInAttempt> lastSuccessFor(String username) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from SignInAttempt where username = :username and"
                                                        + " outcome = :succeeded order by at desc",
                                                SignInAttempt.class)
                                        .setParameter("username", username)
                                        .setParameter("succeeded", SignInOutcome.SUCCEEDED)
                                        .setMaxResults(1)
                                        .getSingleResultOrNull());
    }

    /** One account's own history, newest first. */
    public Uni<List<SignInAttempt>> forUser(Long userId, int limit, int offset) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from SignInAttempt where userId = :userId order by"
                                                        + " at desc",
                                                SignInAttempt.class)
                                        .setParameter("userId", userId)
                                        .setFirstResult(Math.max(0, offset))
                                        .setMaxResults(limit)
                                        .getResultList());
    }

    public Uni<Long> countForUser(Long userId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(a) from SignInAttempt a where"
                                                        + " a.userId = :userId",
                                                Long.class)
                                        .setParameter("userId", userId)
                                        .getSingleResult());
    }

    /** Everything anybody's sign-in was flagged for, newest first. The administrator's view. */
    public Uni<List<SignInAttempt>> flaggedSince(Instant since, int limit, int offset) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from SignInAttempt where anomalies is not null and"
                                                        + " at >= :since order by at desc",
                                                SignInAttempt.class)
                                        .setParameter("since", since)
                                        .setFirstResult(Math.max(0, offset))
                                        .setMaxResults(limit)
                                        .getResultList());
    }

    public Uni<Long> countFlaggedSince(Instant since) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(a) from SignInAttempt a where"
                                                    + " a.anomalies is not null and a.at >= :since",
                                                Long.class)
                                        .setParameter("since", since)
                                        .getSingleResult());
    }

    /** Every failure anywhere since a moment, so the fleet view can show a spray in progress. */
    public Uni<List<SignInAttempt>> failuresSince(Instant since, int limit) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from SignInAttempt where outcome <> :succeeded and"
                                                        + " at >= :since order by at desc",
                                                SignInAttempt.class)
                                        .setParameter("succeeded", SignInOutcome.SUCCEEDED)
                                        .setParameter("since", since)
                                        .setMaxResults(limit)
                                        .getResultList());
    }

    /** Drops what is older than the retention window. Answers how many rows went. */
    public Uni<Integer> deleteOlderThan(Instant cutoff) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "delete from SignInAttempt where at < :cutoff")
                                        .setParameter("cutoff", cutoff)
                                        .executeUpdate());
    }

    private Uni<Long> count(String hql, String parameter, String value, Instant since) {
        if (value == null) {
            return Uni.createFrom().item(0L);
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(hql, Long.class)
                                        .setParameter(parameter, value)
                                        .setParameter("counted", COUNTED)
                                        .setParameter("since", since)
                                        .getSingleResult());
    }
}
