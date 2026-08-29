package io.keydra.security.graphql;

import io.keydra.common.graphql.PageInfo;
import io.keydra.security.dto.AuditEntry;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * A slice of the audit log, and everything needed to ask for the next one.
 *
 * <p>The log is the clearest case for cursors in the whole application: it takes a row every time
 * anybody does anything, including while somebody is reading it. Counted from the start, page two
 * asked for a moment after page one began with a row that had already been on page one — not
 * occasionally, but as the ordinary case, because reading the log is itself preceded by actions
 * that write to it.
 *
 * @param totalCount how many match the filters, across every page
 * @param edges each row with its position
 * @param nodes the same rows without their positions
 * @param pageInfo where this page sits
 */
@Name("AuditConnection")
@Description("A page of the audit log, with the cursors needed to ask for the next")
public record AuditConnection(
        @NonNull @Description("How many match, across every page") int totalCount,
        @NonNull @Description("Each row with its position in the log") List<AuditEdge> edges,
        @NonNull @Description("The same rows, without their positions") List<AuditEntry> nodes,
        @NonNull @Description("Where this page sits within the whole") PageInfo pageInfo) {}
