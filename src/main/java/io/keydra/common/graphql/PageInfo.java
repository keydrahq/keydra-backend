package io.keydra.common.graphql;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * Where a page sits in the whole, so a client knows whether to ask again.
 *
 * <p>Without this a client has to keep asking until an empty answer comes back, which is one wasted
 * request per list every time. The cursors are here as well as on the edges, so a client that only
 * wants to page — and does not care about the edges at all — never has to ask for them.
 *
 * @param startCursor where this page begins, or null when it is empty
 * @param endCursor where this page ends, or null when it is empty
 * @param hasNextPage whether asking again after {@code endCursor} would return anything
 * @param hasPreviousPage whether there is anything before {@code startCursor}
 */
@Name("PageInfo")
@Description("Where a page sits within the whole list")
public record PageInfo(
        @Description("Where this page begins") String startCursor,
        @Description("Where this page ends") String endCursor,
        @NonNull @Description("Whether there is anything after this page") boolean hasNextPage,
        @NonNull @Description("Whether there is anything before this page")
                boolean hasPreviousPage) {

    /** The answer for a page with nothing on it. */
    public static PageInfo empty(boolean hasPreviousPage) {
        return new PageInfo(null, null, false, hasPreviousPage);
    }
}
