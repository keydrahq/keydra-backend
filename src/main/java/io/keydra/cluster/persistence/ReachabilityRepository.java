package io.keydra.cluster.persistence;

import io.keydra.cluster.entity.ReachabilityCheck;
import io.keydra.cluster.entity.ReachabilityEvent;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/** The last answer each outbound thing gave, and nothing before it. */
@ApplicationScoped
public class ReachabilityRepository {

    /** Every answer on record, which the status page reads and nothing else does. */
    @WithSession
    public Uni<List<ReachabilityCheck>> all() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ReachabilityCheck", ReachabilityCheck.class)
                                        .getResultList());
    }

    /**
     * When the newest answer on record was written, or the epoch when there is none.
     *
     * <p>In a session of its own, because the caller is about to walk out to somebody else's
     * services and must not be holding a database connection while it does — the rule {@code
     * BackupRepository.forUse} states for the same reason.
     */
    @WithSession
    public Uni<Instant> newestCheck() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select max(checkedAt) from ReachabilityCheck",
                                                Instant.class)
                                        .getSingleResultOrNull())
                .map(newest -> newest == null ? Instant.EPOCH : newest);
    }

    /** The answers for one kind of thing. */
    @WithSession
    public Uni<List<ReachabilityCheck>> forKind(String kind) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ReachabilityCheck where kind = :kind",
                                                ReachabilityCheck.class)
                                        .setParameter("kind", kind)
                                        .getResultList());
    }

    /**
     * Writes what one thing said, and answers whether that is a change.
     *
     * <p>One statement rather than a read and then a write. The caller announces a change, and a
     * read-then-write would let two walkers — the leader's clock and somebody pressing the button
     * on another instance — both see the old answer and both announce the same edge. The update
     * names the value it will not match, so the row it changes is the row that changed.
     *
     * <p>A subject nobody has asked before is written and is not a change. A fresh destination that
     * has never answered is not news, and announcing it would make adding one an alarm.
     *
     * <p>The history is written here too, in this transaction, and that is not tidiness. The same
     * two walkers that must not both announce an edge must not both record one either — a duplicate
     * message is read once and a duplicate row is read as evidence. So the statement that detects
     * the change is the statement that keeps it, and the state and its history are written together
     * or neither is.
     *
     * @param name what it is called now, kept on the history row as what it was called then
     */
    @WithTransaction
    public Uni<Boolean> record(
            String kind, Long subjectId, String name, boolean ok, String detail, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update ReachabilityCheck set ok = :ok, detail ="
                                                    + " :detail, checkedAt = :at where kind = :kind"
                                                    + " and subjectId = :id and ok <> :ok")
                                        .setParameter("ok", ok)
                                        .setParameter("detail", trimmed(detail))
                                        .setParameter("at", at)
                                        .setParameter("kind", kind)
                                        .setParameter("id", subjectId)
                                        .executeUpdate()
                                        .flatMap(
                                                changed ->
                                                        changed > 0
                                                                ? remember(
                                                                                session, kind,
                                                                                subjectId, name, ok,
                                                                                detail, at)
                                                                        .replaceWith(true)
                                                                : sameAgain(
                                                                        session, kind, subjectId,
                                                                        name, ok, detail, at)));
    }

    /**
     * Keeps one change, which is the only thing worth keeping.
     *
     * <p>Not an answer. Six rows an hour saying what the row before said is a table that grows with
     * the clock and holds nothing, which is what phase 49 refused and was right to.
     */
    private static Uni<Void> remember(
            org.hibernate.reactive.mutiny.Mutiny.Session session,
            String kind,
            Long subjectId,
            String name,
            boolean ok,
            String detail,
            Instant at) {
        ReachabilityEvent event = new ReachabilityEvent();
        event.kind = kind;
        event.subjectId = subjectId;
        event.name = name;
        event.ok = ok;
        event.detail = trimmed(detail);
        event.at = at;
        return session.persist(event).replaceWithVoid();
    }

    /**
     * The row said the same thing, or there was no row.
     *
     * <p>Either way this is not a change. The second update is what tells them apart without
     * reading first: it changes a row that exists, and changes nothing when there is none.
     */
    private Uni<Boolean> sameAgain(
            org.hibernate.reactive.mutiny.Mutiny.Session session,
            String kind,
            Long subjectId,
            String name,
            boolean ok,
            String detail,
            Instant at) {
        return session.createQuery(
                        "update ReachabilityCheck set detail = :detail, checkedAt = :at where kind"
                                + " = :kind and subjectId = :id")
                .setParameter("detail", trimmed(detail))
                .setParameter("at", at)
                .setParameter("kind", kind)
                .setParameter("id", subjectId)
                .executeUpdate()
                .flatMap(
                        touched -> {
                            if (touched > 0) {
                                return Uni.createFrom().item(false);
                            }
                            ReachabilityCheck row = new ReachabilityCheck();
                            row.kind = kind;
                            row.subjectId = subjectId;
                            row.checkedAt = at;
                            row.ok = ok;
                            row.detail = trimmed(detail);
                            // Written to the history and announced to nobody, which is two
                            // questions given two answers rather than one answer used for both. A
                            // first sighting is not news; a timeline whose first entry is a failure
                            // with nothing before it reads as though something broke, where what
                            // happened is that somebody added it that morning.
                            return session.persist(row)
                                    .flatMap(
                                            ignored ->
                                                    remember(
                                                            session, kind, subjectId, name, ok,
                                                            detail, at))
                                    .replaceWith(false);
                        });
    }

    /**
     * Forgets the answers about things of this kind that no longer exist.
     *
     * <p>Instead of a foreign key: the kinds live in different tables, so there is nothing one key
     * could point at. A destination that was deleted stops being reported on the next walk, which
     * is what this does.
     */
    @WithTransaction
    public Uni<Integer> forgetAllBut(String kind, List<Long> alive) {
        if (alive.isEmpty()) {
            return Panache.getSession()
                    .flatMap(
                            session ->
                                    session.createQuery(
                                                    "delete from ReachabilityCheck where kind ="
                                                            + " :kind")
                                            .setParameter("kind", kind)
                                            .executeUpdate());
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from ReachabilityCheck where kind = :kind"
                                                        + " and subjectId not in :alive")
                                        .setParameter("kind", kind)
                                        .setParameter("alive", alive)
                                        .executeUpdate());
    }

    /** Used by the tests to start from nothing. */
    @WithTransaction
    public Uni<Integer> deleteAll() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from ReachabilityCheck")
                                        .executeUpdate());
    }

    /** Bounded so a talkative failure cannot become a column that will not store. */
    private static String trimmed(String detail) {
        if (detail == null) {
            return null;
        }
        String plain = detail.strip();
        return plain.length() <= 500 ? plain : plain.substring(0, 497) + "...";
    }

    /**
     * What has changed, newest first.
     *
     * <p>Everything by default, because "what changed" is the question and the answer is short: a
     * subject that works writes a handful of rows a year. Narrowed to one thing where somebody is
     * asking about one thing.
     */
    @WithSession
    public Uni<List<ReachabilityEvent>> history(String kind, Long subjectId, int limit) {
        String query =
                kind == null
                        ? "from ReachabilityEvent order by at desc, id desc"
                        : subjectId == null
                                ? "from ReachabilityEvent where kind = :kind order by at desc, id"
                                        + " desc"
                                : "from ReachabilityEvent where kind = :kind and subjectId = :id"
                                        + " order by at desc, id desc";
        return Panache.getSession()
                .flatMap(
                        session -> {
                            var typed =
                                    session.createQuery(query, ReachabilityEvent.class)
                                            .setMaxResults(limit);
                            if (kind != null) {
                                typed = typed.setParameter("kind", kind);
                            }
                            if (kind != null && subjectId != null) {
                                typed = typed.setParameter("id", subjectId);
                            }
                            return typed.getResultList();
                        });
    }

    /**
     * Forgets what happened long enough ago that nobody is asking.
     *
     * <p>A history nobody prunes is a table that grows for as long as the application runs. Rare
     * rows make that slow rather than harmless: the thing being kept is evidence, and evidence from
     * three years ago about a destination that has been replaced twice is not evidence of anything.
     */
    @WithTransaction
    public Uni<Integer> forgetHistoryBefore(Instant cutoff) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from ReachabilityEvent where at < :at")
                                        .setParameter("at", cutoff)
                                        .executeUpdate());
    }
}
