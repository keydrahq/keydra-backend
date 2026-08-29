package io.keydra.keys.dto;

import java.util.List;

/**
 * A page of migrations as the service answers it, before it becomes a connection.
 *
 * <p>Deliberately not the GraphQL shape. The service does not know whether its caller wants edges
 * and cursors or a plain array, and giving it a type from one transport would make the other one
 * translate out of it. What it does know is the rows, how many there are, how many are moving, and
 * whether it stopped early — which is everything either transport needs.
 *
 * @param jobs the rows of this page
 * @param total how many match, across every page
 * @param running how many are moving right now, whatever the filters say
 * @param hasMore whether a row beyond this page exists
 */
public record MigrationSlice(List<MigrationJob> jobs, long total, long running, boolean hasMore) {}
