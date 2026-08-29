package io.keydra.authz.dto;

import io.keydra.authz.entity.Permission;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;

/**
 * What one caller may do to one target.
 *
 * <p>A list of these rather than a map keyed by id, which is what the REST shape is. GraphQL has no
 * map type: a {@code Map<String, Set<Permission>>} comes out as a list of entries with generated
 * names, which is a shape nobody would design and nobody can read in a schema. A named pair says
 * the same thing and says what it is.
 *
 * @param connectionId the target
 * @param permissions what the caller holds over it
 */
@Name("TargetPermissions")
@Description("What the caller may do to one target")
public record TargetPermissions(Long connectionId, List<Permission> permissions) {}
