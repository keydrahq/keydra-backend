package io.keydra.keys.graphql;

import io.keydra.keys.dto.MigrationJob;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * One migration and where it sits in the list.
 *
 * <p>The edge exists so the cursor has somewhere to live that is not the migration. A migration has
 * no position — it has a position *in this list, ordered this way, with these filters* — and
 * putting the cursor on the row itself would make that look like a property of the job, which is
 * how a client ends up storing a cursor next to a record and resuming from it in a differently
 * sorted list.
 *
 * @param cursor where to resume from, after this row
 * @param node the migration itself
 */
@Name("MigrationEdge")
@Description("A migration together with its position in the list")
public record MigrationEdge(
        @NonNull @Description("Resume after this row by passing it as `after`") String cursor,
        @NonNull @Description("The migration") MigrationJob node) {}
