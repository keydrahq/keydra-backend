package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.ServerGroupRequest;
import io.keydra.authz.dto.AuthzDtos.ServerGroupSummary;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Named sets of targets, which may sit inside one another.
 *
 * <p>The other half of what keeps the grants table small. A grant on "production" reaches every
 * server in it and in everything below it, so adding a server to a group is what gives people
 * access to it — rather than a round of grants per person per server.
 */
@Path("/api/v1/authz/server-groups")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Server groups", description = "Named sets of targets")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.GROUPS_MANAGE)
public class ServerGroups {

    private final AuthzAdminService service;

    @Inject
    ServerGroups(AuthzAdminService service) {
        this.service = service;
    }

    @GET
    @Operation(summary = "Every server group, with the targets in it")
    @APIResponse(responseCode = "200", description = "Server groups")
    public Uni<List<ServerGroupSummary>> list() {
        return service.serverGroups();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create a server group",
            description = "With a parent, it inherits every grant made on that parent.")
    @APIResponse(responseCode = "201", description = "Created")
    @Audited("servergroup.create")
    public Uni<RestResponse<ServerGroupSummary>> create(@Valid ServerGroupRequest request) {
        return service.createServerGroup(request)
                .map(created -> RestResponse.status(Status.CREATED, created));
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a server group",
            description =
                    "Its children are detached rather than deleted, and the grants on it go with"
                            + " it. The targets themselves are untouched.")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("servergroup.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.deleteServerGroup(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/{id}/servers/{connectionId}")
    @Operation(summary = "Put a target into this group")
    @APIResponse(responseCode = "204", description = "Added")
    @Audited("servergroup.server.add")
    public Uni<RestResponse<Void>> addServer(
            @PathParam("id") Long id, @PathParam("connectionId") Long connectionId) {
        return service.addServerToGroup(id, connectionId).map(ignored -> RestResponse.noContent());
    }

    @DELETE
    @Path("/{id}/servers/{connectionId}")
    @Operation(summary = "Take a target out of this group")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("servergroup.server.remove")
    public Uni<RestResponse<Void>> removeServer(
            @PathParam("id") Long id, @PathParam("connectionId") Long connectionId) {
        return service.removeServerFromGroup(id, connectionId)
                .map(ignored -> RestResponse.noContent());
    }
}
