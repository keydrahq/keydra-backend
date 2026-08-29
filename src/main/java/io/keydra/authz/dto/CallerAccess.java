package io.keydra.authz.dto;

import io.keydra.authz.entity.Permission;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * Everything the caller may do, as the second surface says it.
 *
 * <p>The same answer {@code /api/v1/auth/permissions} gives, with the per-target half as a list of
 * named pairs rather than a map — see {@link TargetPermissions} for why. Both are built from the
 * same service, so they cannot come to disagree about who may do what.
 *
 * @param username who this is about
 * @param securityEnabled whether anything is being enforced at all
 * @param instance what they may do to Keydra itself
 * @param targets what they may do to each target they can see
 */
@Name("CallerAccess")
@Description("What the caller may do, over Keydra itself and over each target")
public record CallerAccess(
        String username,
        @NonNull boolean securityEnabled,
        List<Permission> instance,
        List<TargetPermissions> targets) {}
