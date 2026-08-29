package io.keydra.keys.dto;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;

/**
 * Which column a page of migrations is ordered by.
 *
 * <p>A closed set because it is written into a statement. The column is chosen from this list
 * rather than taken from a caller, which is what makes an orderable table safe to expose at all:
 * there is nothing here to inject into.
 */
@Name("MigrationSort")
@Description("Which column a page of migrations is ordered by")
public enum MigrationSort {
    @Description("The name of the target the keys were read from")
    SOURCE,
    @Description("The name of the target the keys were written to")
    TARGET,
    @Description("When the migration began")
    STARTED,
    @Description("Whether it is moving, done, or something else")
    STATE
}
