package io.keydra.authz.persistence;

import io.keydra.authz.entity.UserSession;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/** The session rows: one per signed-in browser. */
@ApplicationScoped
public class SessionRepository {

    public Uni<UserSession> byId(String id) {
        return Panache.getSession().flatMap(session -> session.find(UserSession.class, id));
    }

    public Uni<UserSession> save(UserSession session) {
        return Panache.getSession()
                .flatMap(persistence -> persistence.persist(session).replaceWith(session));
    }

    /**
     * Somebody's live sessions, newest first.
     *
     * <p>Only the live ones. A list that showed what had lapsed or been ended would be a list
     * somebody has to read past to find the thing they came to check, and "these are the browsers
     * that can act as you right now" is the question this page is opened with.
     */
    public Uni<List<UserSession>> liveFor(Long userId, Instant now) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from UserSession where userId = :userId and"
                                                        + " revokedAt is null and expiresAt > :now"
                                                        + " order by issuedAt desc",
                                                UserSession.class)
                                        .setParameter("userId", userId)
                                        .setParameter("now", now)
                                        .getResultList());
    }

    /**
     * One page of somebody's live sessions, the one they are reading this on first.
     *
     * <p>The ordering is the point. A list that is only newest-first can put the current session on
     * the third page — it was issued whenever this browser last signed in, which is not necessarily
     * the most recent — and that is the row carrying the action that signs somebody out. Sorting it
     * first is one clause here rather than a second request and a merge in the caller.
     *
     * @param current the session id the caller presented, or null when there is none to recognise
     */
    public Uni<List<UserSession>> livePageFor(
            Long userId, Instant now, String current, int first, int offset) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from UserSession where userId = :userId and"
                                                        + " revokedAt is null and expiresAt > :now"
                                                        + " order by case when id = :current then 0"
                                                        + " else 1 end, issuedAt desc",
                                                UserSession.class)
                                        .setParameter("userId", userId)
                                        .setParameter("now", now)
                                        // Never null: a null parameter compared with = matches
                                        // nothing, which is the right answer, but the type has to
                                        // be inferable and an empty string is no session's id.
                                        .setParameter("current", current == null ? "" : current)
                                        .setFirstResult(Math.max(0, offset))
                                        .setMaxResults(first)
                                        .getResultList());
    }

    /** How many there are to page through. What a pager needs and a page of rows cannot say. */
    public Uni<Long> liveCountFor(Long userId, Instant now) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(*) from UserSession where userId ="
                                                    + " :userId and revokedAt is null and expiresAt"
                                                    + " > :now",
                                                Long.class)
                                        .setParameter("userId", userId)
                                        .setParameter("now", now)
                                        .getSingleResult());
    }

    /** Ends one session, if it is the caller's to end. Answers how many rows that changed. */
    public Uni<Integer> revoke(String id, Long userId, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update UserSession set revokedAt = :at where id ="
                                                    + " :id and userId = :userId and revokedAt is"
                                                    + " null")
                                        .setParameter("at", at)
                                        .setParameter("id", id)
                                        .setParameter("userId", userId)
                                        .executeUpdate());
    }

    /**
     * Ends every session an account has, except one.
     *
     * <p>The exception is what makes this usable: somebody who has just changed their password, or
     * who has just pressed "sign out everywhere else", is themselves signed in — and ending their
     * own session as well would answer a click about safety by throwing them out.
     */
    public Uni<Integer> revokeAllExcept(Long userId, String keep, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update UserSession set revokedAt = :at where"
                                                    + " userId = :userId and revokedAt is null and"
                                                    + " id <> :keep")
                                        .setParameter("at", at)
                                        .setParameter("userId", userId)
                                        .setParameter("keep", keep == null ? "" : keep)
                                        .executeUpdate());
    }

    /** Notes that a session is in use. Called on a slow clock rather than on every request. */
    public Uni<Integer> touch(String id, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "update UserSession set lastSeenAt = :at where id ="
                                                        + " :id")
                                        .setParameter("at", at)
                                        .setParameter("id", id)
                                        .executeUpdate());
    }

    /**
     * Removes what has lapsed.
     *
     * <p>A session table nobody prunes is a table that grows for as long as the application runs,
     * and what it grows with is a record of where somebody was working and when.
     */
    public Uni<Integer> sweepExpired(Instant before) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createMutationQuery(
                                                "delete from UserSession where expiresAt < :before")
                                        .setParameter("before", before)
                                        .executeUpdate());
    }
}
