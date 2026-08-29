package io.keydra.security.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.engine.AclUser;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.keydra.security.dto.AclUserRequest;
import io.keydra.security.service.AclService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The users a target itself knows about.
 *
 * <p>Admin only, and audited: changing who may reach a database is exactly the kind of act a log
 * exists for.
 */
@Path("/api/v1/connections/{connectionId}/acl")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(Roles.ADMIN)
@Tag(name = "Security", description = "Identity, roles and the audit log")
public class Acl {

    private final AclService service;

    @Inject
    Acl(AclService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Users the target knows about",
            description =
                    "Password hashes are never returned: a UI cannot use one, an attacker can.")
    @APIResponse(responseCode = "200", description = "The target's users")
    @RequiresPermission(value = Permission.ACL_READ, connection = "connectionId")
    public Uni<List<AclUser>> users(@PathParam("connectionId") Long connectionId) {
        return service.users(connectionId);
    }

    @GET
    @Path("/categories")
    @Operation(summary = "Permission categories the target defines")
    @APIResponse(responseCode = "200", description = "Category names")
    @RequiresPermission(value = Permission.ACL_READ, connection = "connectionId")
    public Uni<List<String>> categories(@PathParam("connectionId") Long connectionId) {
        return service.categories(connectionId);
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Audited("acl.setUser")
    @Operation(
            summary = "Create or replace a user",
            description =
                    "Rules are passed to the target unaltered. The rule language belongs to the"
                            + " server and grows with its versions; a form that only knew the rules"
                            + " Keydra was written against would prevent the rest from being"
                            + " written at all.")
    @APIResponse(responseCode = "204", description = "The user was created or replaced")
    @RequiresPermission(value = Permission.ACL_MANAGE, connection = "connectionId")
    public Uni<Response> setUser(
            @PathParam("connectionId") Long connectionId, @Valid AclUserRequest request) {
        return service.setUser(connectionId, request.username(), request.rules())
                .replaceWith(Response.noContent().build());
    }

    @DELETE
    @Path("/{username}")
    @Audited("acl.deleteUser")
    @Operation(summary = "Remove a user from the target")
    @APIResponse(responseCode = "204", description = "The user was removed")
    @APIResponse(responseCode = "404", description = "The target had no user of that name")
    @RequiresPermission(value = Permission.ACL_MANAGE, connection = "connectionId")
    public Uni<Response> deleteUser(
            @PathParam("connectionId") Long connectionId, @PathParam("username") String username) {
        return service.deleteUser(connectionId, username)
                .map(
                        removed ->
                                Boolean.TRUE.equals(removed)
                                        ? Response.noContent().build()
                                        : Response.status(Response.Status.NOT_FOUND).build());
    }
}
