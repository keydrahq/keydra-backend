package io.keydra.keys.graphql;

import io.keydra.common.graphql.PageInfo;
import io.keydra.keys.dto.MigrationJob;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * A slice of the migrations, and everything needed to ask for the next one.
 *
 * <p>A connection rather than a list with an offset, which is what this was. Offsets count from the
 * start of a list, and this list grows at the start: every migration somebody runs goes to the
 * front. Turning to page two a moment after reading page one showed a row that had already been on
 * page one, because the row that pushed it down arrived in between. A cursor says "after this one"
 * instead of "from the twentieth", and a row arriving in front of it changes nothing.
 *
 * <p>Both {@code edges} and {@code nodes} are here. The edges carry the cursors, which is where a
 * cursor belongs — it is a property of a position in a list, not of a migration. But a client that
 * only wants the rows and takes its cursors from {@code pageInfo} should not have to walk a layer
 * of wrappers to reach them, so the rows are offered directly as well.
 *
 * @param totalCount how many match, across every page
 * @param running how many are moving right now, whatever the filters say
 * @param edges each row with its position
 * @param nodes the same rows without their positions
 * @param pageInfo where this page sits
 */
@Name("MigrationConnection")
@Description("A page of migrations, with the cursors needed to ask for the next")
public record MigrationConnection(
        @NonNull @Description("How many match, across every page") int totalCount,
        @NonNull @Description("How many are moving right now, whatever the filters say")
                int running,
        @NonNull @Description("Each row with its position in the list") List<MigrationEdge> edges,
        @NonNull @Description("The same rows, without their positions") List<MigrationJob> nodes,
        @NonNull @Description("Where this page sits within the whole") PageInfo pageInfo) {}
