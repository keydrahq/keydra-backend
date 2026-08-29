package io.keydra.security.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.Cursors;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.common.graphql.PageInfo;
import io.keydra.security.Roles;
import io.keydra.security.dto.AuditEntry;
import io.keydra.security.dto.AuditQuery;
import io.keydra.security.mapper.AuditMapper;
import io.keydra.security.service.AuditService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * What was done, by whom, and whether it was allowed.
 *
 * <p>Guarded exactly as the resource is, and by both of the things that guard it. The role
 * annotation is the coarse gate: it decides whether somebody is anybody. The permission is the real
 * answer, and it is read from the grant tables rather than from a name — which is what makes a role
 * somebody defined themselves work here at all.
 *
 * <p>A connection rather than a list with an offset. The log grows at the front while it is being
 * read, so a page counted from the start is a page that has moved by the time it is asked for.
 */
@GraphQLApi
@OneAtATime
public class AuditQueries {

    /** What a table shows at once, and the most a caller may ask for in one go. */
    private static final int DEFAULT_PAGE = 20;

    private static final int MAX_PAGE = 200;

    /** The log has one ordering, so a cursor from it can only mean one thing. */
    private static final String ORDERING = "NEWEST";

    private final AuditService audit;
    private final AuditMapper mapper;

    @Inject
    AuditQueries(AuditService audit, AuditMapper mapper) {
        this.audit = audit;
        this.mapper = mapper;
    }

    @Query("auditLog")
    @Description("A page of the audit log, newest first")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    public Uni<AuditConnection> auditLog(
            @Name("first") @DefaultValue("20") @Description("How many rows to return")
                    Integer first,
            @Name("after") @Description("Resume after this cursor") String after,
            @Name("actor") @Description("Only this account's actions") String actor,
            @Name("action") @Description("Only this kind of action") String action,
            @Name("connectionId") @Description("Only actions against this target")
                    Long connectionId,
            @Name("since") @Description("Only what happened after this moment") Instant since) {
        // Clamped rather than trusted: a page size is an argument, and an argument is whatever the
        // caller sent. Without this one query asks for the whole log.
        int size = first == null ? DEFAULT_PAGE : Math.min(Math.max(first, 1), MAX_PAGE);
        // A cursor that cannot be read starts from the beginning rather than failing. It is a
        // position, and a position that no longer exists is not an error somebody can act on.
        Cursors.Position from = Cursors.read(after, ORDERING);

        return audit.page(new AuditQuery(actor, action, connectionId, since), from, size)
                .map(
                        rows -> {
                            List<AuditEntry> entries = mapper.toEntries(rows.rows());
                            return connection(entries, rows.total(), rows.hasMore(), from != null);
                        });
    }

    @Query("auditActions")
    @Description("Every kind of action that has been recorded, for a filter to offer")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    public Uni<List<String>> auditActions() {
        return audit.actions();
    }

    private static AuditConnection connection(
            List<AuditEntry> entries, long total, boolean hasMore, boolean resumed) {
        List<AuditEdge> edges =
                entries.stream()
                        .map(entry -> new AuditEdge(Cursors.of(ORDERING, null, entry.id()), entry))
                        .toList();
        PageInfo where =
                edges.isEmpty()
                        ? PageInfo.empty(resumed)
                        : new PageInfo(
                                edges.getFirst().cursor(),
                                edges.getLast().cursor(),
                                hasMore,
                                resumed);
        return new AuditConnection((int) total, edges, entries, where);
    }
}
