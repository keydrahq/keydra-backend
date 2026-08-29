package io.keydra.common.graphql;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;

/**
 * Something with an identity of its own, that can be asked for by that identity alone.
 *
 * <p>GraphQL's global object identification, and the reason it exists is caching. A client that
 * holds two answers mentioning the same migration has no way to know they are the same migration if
 * each answer only carries a number that is unique within its own table — so it keeps both, and one
 * of them goes stale. An id that is unique across the whole schema lets a client keep one copy of
 * each thing, update it once, and refetch it on its own.
 *
 * <p>The id is opaque on purpose. It is a type and a key together (see {@link GlobalId}), and a
 * client that took it apart would be depending on the key being a database row — which is exactly
 * the thing this is meant to stop leaking.
 */
@Name("Node")
@Description("An object with an identity of its own")
public interface Node {

    @Id
    @Description("Unique across the whole schema, and opaque")
    String getId();
}
