package io.keydra.keys.graphql;

import io.keydra.common.graphql.Cursors;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.common.graphql.PageInfo;
import io.keydra.connections.dto.ConnectionResponse;
import io.keydra.connections.service.ConnectionService;
import io.keydra.keys.dto.MigrationJob;
import io.keydra.keys.dto.MigrationQuery;
import io.keydra.keys.dto.MigrationSlice;
import io.keydra.keys.dto.MigrationSort;
import io.keydra.keys.service.KeyMigrationService;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;

/**
 * Migrations, asked for by whoever is showing them.
 *
 * <p>Transport only, like a resource: it calls the same service the REST endpoints call, which is
 * what makes the two surfaces impossible to disagree. That service already filters to what the
 * caller may see, at both ends of every migration — a resolver that reached the repository directly
 * would be a second door into the same house.
 *
 * <p>A connection rather than a list. What the table needs is a page, and what a page needs is a
 * position to resume from; an offset into a list that grows at the front is a position that means
 * something different by the time it is used.
 *
 * <p>A migration also names its two targets as targets rather than as numbers. That is the point of
 * the second surface: the page used to ask for the whole connection catalogue alongside the rows so
 * a row could say "payments-cache" instead of "3", and joining them by hand in a browser is exactly
 * the work a graph exists to avoid. The names are fetched for the whole page at once, not per row —
 * see {@link #source(List)}.
 */
@GraphQLApi
@OneAtATime
public class MigrationQueries {

    /** What a table shows at once, and the most a caller may ask for in one go. */
    private static final int DEFAULT_PAGE = 20;

    private static final int MAX_PAGE = 200;

    private final KeyMigrationService migrations;
    private final ConnectionService connections;

    @Inject
    MigrationQueries(KeyMigrationService migrations, ConnectionService connections) {
        this.migrations = migrations;
        this.connections = connections;
    }

    @Query("migrations")
    @Description("A page of migrations, newest first, filtered to what the caller can see")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    public Uni<MigrationConnection> migrations(
            @Name("first") @DefaultValue("20") @Description("How many rows to return")
                    Integer first,
            @Name("after") @Description("Resume after this cursor") String after,
            @Name("search") @Description("Match either target's name") String search,
            @Name("state") @Description("Only migrations in this state") MigrationJob.State state,
            @Name("sort") @DefaultValue("STARTED") @Description("Which column to order by")
                    MigrationSort sort,
            @Name("descending") @DefaultValue("true") @Description("Newest or largest first")
                    Boolean descending) {
        // Clamped rather than trusted: a page size is an argument, and an argument is whatever the
        // caller sent. Without this one query asks for the whole history.
        int size = first == null ? DEFAULT_PAGE : Math.min(Math.max(first, 1), MAX_PAGE);
        MigrationSort ordering = sort == null ? MigrationSort.STARTED : sort;
        boolean newestFirst = descending == null || descending;
        String ordered = ordering(ordering, newestFirst);

        // A cursor that cannot be read here — an older format, or one taken from a differently
        // sorted list — starts from the beginning rather than failing. It is a position, and a
        // position that no longer exists is not an error somebody can act on.
        Cursors.Position from = Cursors.read(after, ordered);

        MigrationQuery query = new MigrationQuery(null, state, search, ordering, newestFirst);

        return migrations
                .pageOfJobs(query, from, size)
                .map(paged -> connection(paged, ordered, ordering, from != null));
    }

    /**
     * The target a migration read from, for every row on the page at once.
     *
     * <p>A batched resolver, which is what stops a page of twenty rows becoming forty lookups. The
     * naive shape of this — a method taking one migration — is the N+1 problem the best practices
     * warn about, and it is worse here than usual: every one of those lookups would decrypt a
     * stored password to build a profile whose name is the only field anybody wanted.
     *
     * <p>Answered from the catalogue the caller can see, so a migration whose source has since been
     * deleted, or which names a target this caller cannot reach, resolves to null rather than
     * leaking that it exists. The list this returns is positional: one entry per migration, in
     * order, which is the contract a batched resolver has.
     */
    @Name("source")
    @Description("The target the keys were read from")
    public Uni<List<ConnectionResponse>> source(@Source List<MigrationJob> jobs) {
        return named(jobs, MigrationJob::sourceConnectionId);
    }

    @Name("target")
    @Description("The target the keys were written to")
    public Uni<List<ConnectionResponse>> target(@Source List<MigrationJob> jobs) {
        return named(jobs, MigrationJob::targetConnectionId);
    }

    private Uni<List<ConnectionResponse>> named(
            List<MigrationJob> jobs, Function<MigrationJob, Long> which) {
        Set<Long> wanted = jobs.stream().map(which).collect(Collectors.toSet());
        return connections
                .list()
                .map(
                        catalogue -> {
                            Map<Long, ConnectionResponse> byId =
                                    catalogue.stream()
                                            .filter(one -> wanted.contains(one.id()))
                                            .collect(
                                                    Collectors.toMap(
                                                            ConnectionResponse::id,
                                                            Function.identity()));
                            return jobs.stream().map(job -> byId.get(which.apply(job))).toList();
                        });
    }

    // --- Turning a page of rows into a connection ---------------------------

    private static MigrationConnection connection(
            MigrationSlice paged, String ordered, MigrationSort sort, boolean resumed) {
        List<MigrationEdge> edges =
                paged.jobs().stream()
                        .map(job -> new MigrationEdge(cursorOf(job, ordered, sort), job))
                        .toList();
        PageInfo where =
                edges.isEmpty()
                        ? PageInfo.empty(resumed)
                        : new PageInfo(
                                edges.getFirst().cursor(),
                                edges.getLast().cursor(),
                                paged.hasMore(),
                                resumed);
        return new MigrationConnection(
                (int) paged.total(), (int) paged.running(), edges, paged.jobs(), where);
    }

    /**
     * A cursor for one row, holding the value this list is ordered by.
     *
     * <p>The value has to be the one the statement compares against, or resuming lands somewhere
     * else entirely. The two name sorts are the awkward case: the row does not hold the name, so
     * there is nothing here to write down — the cursor carries the id alone and the statement
     * resumes on the id within an equal name, which is exact for every row except the boundary
     * between two identically named targets, and two targets cannot share a name.
     */
    private static String cursorOf(MigrationJob job, String ordered, MigrationSort sort) {
        String value =
                switch (sort) {
                    case STARTED -> job.startedAt() == null ? null : job.startedAt().toString();
                    case STATE -> job.state() == null ? null : job.state().name();
                    case SOURCE, TARGET -> null;
                };
        return Cursors.of(ordered, value, job.id());
    }

    /** How an ordering is named inside a cursor, so one from another ordering is refused. */
    private static String ordering(MigrationSort sort, boolean descending) {
        return sort.name() + (descending ? "-desc" : "-asc");
    }
}
