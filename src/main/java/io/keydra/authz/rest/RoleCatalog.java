package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.RoleRequest;
import io.keydra.authz.dto.AuthzDtos.RoleSummary;
import io.keydra.authz.dto.PermissionInfo;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.AuthzAdminService;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Named bundles of permissions.
 *
 * <p>Three of them are built in and cannot be edited: what {@code viewer}, {@code operator} and
 * {@code admin} carry is defined in code and rewritten at every start, so an edit would be undone
 * by the next restart — which is worse than a refusal, because it would appear to have worked.
 */
@Path("/api/v1/authz/roles")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Roles", description = "Named bundles of permissions")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.GRANTS_MANAGE)
public class RoleCatalog {

    private final AuthzAdminService service;

    @Inject
    RoleCatalog(AuthzAdminService service) {
        this.service = service;
    }

    @GET
    @Operation(summary = "Every role and what it carries")
    @APIResponse(responseCode = "200", description = "Roles")
    public Uni<List<RoleSummary>> list() {
        return service.roles();
    }

    @GET
    @Path("/permissions")
    @Operation(
            summary = "Every permission there is",
            description =
                    "The closed list a custom role is built from. Sent by the server rather than"
                            + " kept in the interface, so a permission added to the backend appears"
                            + " in the role editor without a second change.")
    @APIResponse(responseCode = "200", description = "Permission identifiers")
    public Uni<List<PermissionInfo>> permissions() {
        return Uni.createFrom()
                .item(
                        Arrays.stream(Permission.values())
                                .map(
                                        permission ->
                                                new PermissionInfo(
                                                        permission.name(),
                                                        permission.id(),
                                                        permission.level().name()))
                                .toList());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a custom role")
    @APIResponse(responseCode = "201", description = "Created")
    @APIResponse(responseCode = "409", description = "That name is taken")
    @Audited("role.create")
    public Uni<RestResponse<RoleSummary>> create(@Valid RoleRequest request) {
        return service.createRole(request)
                .map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Change a custom role")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "Built-in roles cannot be edited")
    @Audited("role.update")
    public Uni<RoleSummary> update(@PathParam("id") Long id, @Valid RoleRequest request) {
        return service.updateRole(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a custom role",
            description = "Every grant that named it goes with it.")
    @APIResponse(responseCode = "204", description = "Removed")
    @APIResponse(responseCode = "409", description = "Built-in roles cannot be deleted")
    @Audited("role.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.deleteRole(id).map(ignored -> RestResponse.noContent());
    }
}
