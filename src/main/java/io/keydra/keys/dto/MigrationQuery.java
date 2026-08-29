package io.keydra.keys.dto;

import java.util.Set;

/**
 * What narrows and orders a page of migrations.
 *
 * <p>A value object rather than six parameters threaded through three layers: the transport builds
 * one, the service passes it on, the repository reads it. Adding a filter then means adding a field
 * here instead of changing every signature between the two ends.
 *
 * @param targets only migrations between these, or null for every target the caller can see
 * @param state only migrations in this state, or null for any
 * @param search text to match against either target's name, or null for all of them
 * @param sort which column to order by
 * @param descending newest or largest first
 */
public record MigrationQuery(
        Set<Long> targets,
        MigrationJob.State state,
        String search,
        MigrationSort sort,
        boolean descending) {

    /** Whether anything was typed to search for. Blank is not a search, it is an empty box. */
    public boolean searching() {
        return search != null && !search.isBlank();
    }
}
