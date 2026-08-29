package io.keydra.keys.persistence;

import static java.util.stream.Collectors.toSet;

import io.keydra.common.graphql.Cursors;
import io.keydra.keys.dto.MigrationJob;
import io.keydra.keys.dto.MigrationQuery;
import io.keydra.keys.dto.MigrationSort;
import io.keydra.keys.entity.MigrationRun;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * What has been moved, and what was being moved when the lights went out.
 *
 * <p>Every method opens its own transaction, because none of them happens inside a request: a
 * migration runs detached from the call that started it, and the writes here are checkpoints taken
 * while it walks a keyspace. A session held across that walk would be a database connection held
 * across it.
 */
@ApplicationScoped
public class MigrationRepository {

    /** How many migrations a history shows. Enough to answer "did last night's copy finish". */
    public static final int HISTORY = 100;

    @WithTransaction
    public Uni<MigrationRun> start(MigrationRun run) {
        return Panache.getSession().flatMap(session -> session.persist(run).replaceWith(run));
    }

    /**
     * Writes a job's counters onto its row: at a checkpoint, and when it ends.
     *
     * <p>A row that has already ended is left alone. Checkpoints are written and forgotten while
     * the walk goes on, so one of them can still be in the air when the job finishes — and applying
     * it afterwards would put a finished migration back to RUNNING, where it would stay until the
     * next restart called it interrupted. The ending is the one write that must not be overtaken.
     */
    @WithTransaction
    public Uni<Boolean> record(MigrationJob job, String instanceId) {
        return Panache.getSession()
                .flatMap(session -> session.find(MigrationRun.class, job.id()))
                .map(
                        run -> {
                            if (run == null || hasEnded(run.state)) {
                                return false;
                            }
                            /*
                             * Only the instance the row names may write to it, and the answer says
                             * so. A walk can be taken over while it is still going — that is the
                             * point of the stale check — and without this the instance that lost
                             * the row would carry on writing counters onto a job somebody else is
                             * now doing. Told it no longer owns the row, it stops.
                             *
                             * A row with no name is an old one, from before a migration recorded
                             * whose it was. Refusing those would refuse the checkpoints of a job
                             * that is running perfectly well.
                             */
                            if (run.instanceId != null && !run.instanceId.equals(instanceId)) {
                                return false;
                            }
                            run.apply(job);
                            // Any write from the owner is proof the owner is alive, so the claim
                            // is renewed by the checkpoint rather than by a heartbeat of its own.
                            // A job that has stopped writing has stopped, and that is the same
                            // sentence either way.
                            run.claimedAt = Instant.now();
                            return true;
                        });
    }

    /**
     * Migrations nobody is walking any more.
     *
     * <p>Two ways to be abandoned and both are needed. A row whose instance is not in the roster is
     * one whose process is gone — that is the ordinary case, an instance killed mid-walk. A row
     * whose claim has gone stale is one whose instance is still announcing itself but has stopped
     * writing checkpoints, which is what a hung walk or a lost connection to a source looks like
     * from here.
     *
     * <p>Asked with the live instances rather than the dead ones, because the dead ones are not a
     * list anybody has: an instance that is gone is gone from the roster too, and the only thing
     * that can be enumerated is who is still here.
     */
    @WithSession
    public Uni<List<MigrationRun>> abandoned(Collection<String> live, Instant staleBefore) {
        // An empty list would make "not in ()" a statement PostgreSQL refuses, and there is always
        // at least the instance doing the asking — but a sweep that threw on an empty roster would
        // be a sweep that fails exactly when something is very wrong.
        Collection<String> names = live.isEmpty() ? List.of("") : live;
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from MigrationRun where state = :running and"
                                                    + " (instanceId is null or instanceId not in"
                                                    + " (:live) or claimedAt is null or claimedAt <"
                                                    + " :stale)",
                                                MigrationRun.class)
                                        .setParameter("running", MigrationJob.State.RUNNING)
                                        .setParameter("live", names)
                                        .setParameter("stale", staleBefore)
                                        .setMaxResults(HISTORY)
                                        .getResultList());
    }

    /**
     * Takes a row over, and answers whether it was this instance that took it.
     *
     * <p>The whole handover rests on this being one statement. Two instances can decide at the same
     * moment that the same migration is abandoned — they are reading the same table — and the one
     * that matters is whichever database row is updated first. Naming the previous owner in the
     * {@code where} makes the second update match nothing, so the second instance learns it lost by
     * being told it changed no rows rather than by discovering later that two walks are running.
     */
    @WithTransaction
    public Uni<Boolean> claim(String id, String from, String to) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update MigrationRun set instanceId = :to,"
                                                    + " claimedAt = :now, resumed = resumed + 1,"
                                                    + " scanned = 0, migrated = 0, skipped = 0,"
                                                    + " failed = 0, dropped = 0, deleted = 0 where"
                                                    + " id = :id and state = :running and"
                                                    + " (instanceId = :from or (:from is null and"
                                                    + " instanceId is null))")
                                        .setParameter("to", to)
                                        .setParameter("from", from)
                                        .setParameter("now", Instant.now())
                                        .setParameter("id", id)
                                        .setParameter("running", MigrationJob.State.RUNNING)
                                        .executeUpdate())
                .map(rows -> rows == 1);
    }

    /** Whether a state is one nothing moves out of. */
    private static boolean hasEnded(MigrationJob.State state) {
        return state != null && state != MigrationJob.State.RUNNING;
    }

    @WithSession
    public Uni<List<MigrationRun>> recent() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from MigrationRun order by startedAt desc",
                                                MigrationRun.class)
                                        .setMaxResults(HISTORY)
                                        .getResultList());
    }

    /**
     * One page of runs between targets the caller can see, and how many there are in all.
     *
     * <p>Narrowed in the statement rather than after it, which is the difference between paging and
     * pretending to. The list used to be capped at a hundred rows and cut into pages in the
     * browser: every visit fetched the hundred whatever the table showed, and the hundred and first
     * migration was simply not reachable. Both ends are checked because a migration names two
     * targets, and somebody who can reach neither has no business knowing that keys moved between
     * them.
     *
     * <p>An empty set of visible targets short-circuits: {@code in ()} is not valid SQL, and the
     * answer is known without asking.
     */
    public Uni<MigrationRows> page(
            Set<Long> visible, MigrationQuery query, Cursors.Position after, int size) {
        Set<Long> reachable =
                query.targets() == null || query.targets().isEmpty()
                        ? visible
                        // Narrowed by both: what the caller asked to see, and what they may.
                        // The second is not negotiable, so it is applied to the first rather
                        // than instead of it.
                        : query.targets().stream().filter(visible::contains).collect(toSet());
        if (reachable.isEmpty()) {
            return Uni.createFrom().item(new MigrationRows(List.of(), 0, 0, false));
        }

        StringBuilder where =
                new StringBuilder(
                        "from MigrationRun where sourceConnectionId in :targets and"
                                + " targetConnectionId in :targets");
        if (query.state() != null) {
            where.append(" and state = :state");
        }
        if (query.searching()) {
            // Matched against the names, which is what somebody typing into the box is looking
            // at — the row holds ids. Resolving it in the browser instead would search only the
            // targets that browser had loaded, and only the page it was holding.
            where.append(
                    " and (lower((select p.name from ConnectionProfile p where p.id ="
                        + " sourceConnectionId)) like :needle or lower((select p.name from"
                        + " ConnectionProfile p where p.id = targetConnectionId)) like :needle)");
        }
        String from = where.toString();
        // The cursor narrows the rows and never the counts. "How many are there" is a question
        // about the list, not about where somebody has read up to — a total that shrank as you
        // paged would be a total of what is left, which is not what a pager shows.
        String rowsFrom = after == null ? from : from + after(query);

        return Panache.getSession()
                .flatMap(
                        session -> {
                            var rows =
                                    session.createQuery(
                                            rowsFrom + orderBy(query), MigrationRun.class);
                            var counted =
                                    session.createQuery("select count(*) " + from, Long.class);
                            rows.setParameter("targets", reachable);
                            counted.setParameter("targets", reachable);
                            if (query.state() != null) {
                                rows.setParameter("state", query.state());
                                counted.setParameter("state", query.state());
                            }
                            if (query.searching()) {
                                String needle =
                                        "%"
                                                + query.search()
                                                        .trim()
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                + "%";
                                rows.setParameter("needle", needle);
                                counted.setParameter("needle", needle);
                            }
                            // Counted over every target the caller can see and not narrowed by
                            // the state filter: the summary line says how many are moving right
                            // now, and that answer does not change because somebody is looking at
                            // the finished ones.
                            var moving =
                                    session.createQuery(
                                                    "select count(*) from MigrationRun where"
                                                            + " sourceConnectionId in :targets and"
                                                            + " targetConnectionId in :targets and"
                                                            + " state = :running",
                                                    Long.class)
                                            .setParameter("targets", reachable)
                                            .setParameter("running", MigrationJob.State.RUNNING);
                            if (after != null) {
                                rows.setParameter("cursorValue", sortValue(query.sort(), after));
                                // A migration is identified by a UUID, not a row number: bound as
                                // the string it is.
                                rows.setParameter("cursorId", after.id());
                            }
                            // One more than asked for, and it is never returned. Whether it comes
                            // back is the whole answer to "is there a next page" — the alternative
                            // is a second count with the cursor applied, which is another query
                            // per page to learn one bit.
                            return rows.setMaxResults(size + 1)
                                    .getResultList()
                                    .flatMap(
                                            found ->
                                                    // One after another rather than together:
                                                    // one session serves one statement at a time.
                                                    counted.getSingleResult()
                                                            .flatMap(
                                                                    total ->
                                                                            moving.getSingleResult()
                                                                                    .map(
                                                                                            live ->
                                                                                                    paged(
                                                                                                            found,
                                                                                                            size,
                                                                                                            total,
                                                                                                            live))));
                        });
    }

    /** Trims the extra row off, and says whether there was one. */
    private static MigrationRows paged(
            List<MigrationRun> found, int size, long total, long running) {
        boolean more = found.size() > size;
        return new MigrationRows(more ? found.subList(0, size) : found, total, running, more);
    }

    /**
     * The value in a cursor, as the column it is compared against.
     *
     * <p>A cursor is text — it travels through a browser — and the column it resumes from is a
     * timestamp, an enum or a name. Binding the text against a timestamp column is the sort of
     * thing that works on one database and not the next.
     */
    private static Object sortValue(MigrationSort sort, Cursors.Position position) {
        String held = position.sortValue();
        return switch (sort) {
            case STARTED -> held == null ? null : Instant.parse(held);
            case STATE -> held == null ? null : MigrationJob.State.valueOf(held);
            case SOURCE, TARGET -> held;
        };
    }

    /**
     * What to sort by, written as an expression rather than a column where it has to be.
     *
     * <p>Two of the sortable columns show a target's name, and a migration row holds only its id.
     * Ordering by the id would put the servers in the order somebody happened to add them, which is
     * not what a column headed "source" sorting alphabetically claims to do — so the name is looked
     * up in the order-by itself. The alternative was sorting the twenty rows of a page, which is a
     * sort that disagrees with its own pager.
     *
     * <p>The column name never comes from the caller: it is chosen from this list by an enum, so
     * there is nothing here to inject into.
     */
    private static String orderBy(MigrationQuery query) {
        String direction = query.descending() ? " desc" : " asc";
        // The id decides ties, in the same direction. Without it two rows sharing a state — or a
        // timestamp, or a target name — have no fixed order between them, and a cursor that says
        // "after that one" cannot mean anything: the same row can sit either side of the boundary
        // on two runs of the same query, so paging both repeats and skips.
        return " order by " + sortExpression(query.sort()) + direction + ", id" + direction;
    }

    /**
     * The value a list is ordered by, written as an expression.
     *
     * <p>Two of the sortable columns show a target's name, and a migration row holds only its id.
     * Ordering by the id would put the servers in the order somebody happened to add them, which is
     * not what a column headed "source" sorting alphabetically claims to do — so the name is looked
     * up here. The alternative was sorting the twenty rows of a page, which is a sort that
     * disagrees with its own pager.
     *
     * <p>The same expression is used for the cursor predicate, so what a page resumes from is
     * always the thing it was ordered by. Two spellings of that would be two chances to disagree.
     *
     * <p>Never built from anything a caller sent: the column is chosen from this list by an enum,
     * so there is nothing here to inject into.
     */
    private static String sortExpression(MigrationSort sort) {
        return switch (sort) {
            case SOURCE ->
                    "(select p.name from ConnectionProfile p where p.id = sourceConnectionId)";
            case TARGET ->
                    "(select p.name from ConnectionProfile p where p.id = targetConnectionId)";
            case STATE -> "state";
            case STARTED -> "startedAt";
        };
    }

    /**
     * The clause that skips everything up to and including the row a cursor names.
     *
     * <p>Written out rather than as a row-value comparison, which not every database spells the
     * same way: strictly past the sort value, or level with it and strictly past the id. The id is
     * what makes it exact — level with the sort value there may be many rows, and without the
     * second half the whole group would be skipped or repeated.
     *
     * <p>A row whose sort value is null is not resumed from. Only the target-name sorts can produce
     * one, and only for a migration naming a target that has since been deleted; the alternative is
     * three-valued logic in the middle of a pagination predicate, and a page that silently loses
     * rows is worse than one that starts a row late.
     */
    private static String after(MigrationQuery query) {
        String comparison = query.descending() ? "<" : ">";
        String expression = sortExpression(query.sort());
        return " and ("
                + expression
                + " "
                + comparison
                + " :cursorValue or ("
                + expression
                + " = :cursorValue and id "
                + comparison
                + " :cursorId))";
    }

    /**
     * Migrations this instance's name is on.
     *
     * <p>Asked at startup and on every sweep, because a row under this instance's own name that
     * this instance is not walking is unambiguous: either the process it belonged to is gone and
     * this is the same instance come back, or the walk stopped without recording an ending. Either
     * way there is nobody to ask and nothing to race — it is already ours — so it can simply be
     * started again.
     *
     * <p>This is what a restart used to do differently. It marked its own running rows interrupted,
     * which was honest and was as far as it went: the work stopped and stayed stopped until
     * somebody noticed and asked for it again.
     */
    @WithSession
    public Uni<List<MigrationRun>> ownedBy(String instanceId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from MigrationRun where state = :running and"
                                                        + " instanceId = :instance",
                                                MigrationRun.class)
                                        .setParameter("running", MigrationJob.State.RUNNING)
                                        .setParameter("instance", instanceId)
                                        .setMaxResults(HISTORY)
                                        .getResultList());
    }
}
