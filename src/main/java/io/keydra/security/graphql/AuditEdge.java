package io.keydra.security.graphql;

import io.keydra.security.dto.AuditEntry;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * One audit entry and where it sits in the log.
 *
 * <p>The cursor lives here rather than on the entry, because a position belongs to a list and not
 * to the thing in it. An entry is the same entry whichever way the log is filtered; where it sits
 * is not.
 *
 * @param cursor where to resume from, after this row
 * @param node the entry itself
 */
@Name("AuditEdge")
@Description("An audit entry together with its position in the log")
public record AuditEdge(
        @NonNull @Description("Resume after this row by passing it as `after`") String cursor,
        @NonNull @Description("The entry") AuditEntry node) {}
