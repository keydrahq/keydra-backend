package io.keydra.topology.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Roles;
import io.keydra.topology.dto.TargetTopology;
import io.keydra.topology.service.TopologyService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** How a target is arranged, and what it will let Keydra do. */
@Path("/api/v1/connections/{connectionId}/topology")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Topology", description = "Cluster nodes, sentinel discovery and capabilities")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class TopologyResource {

    private final TopologyService service;

    @Inject
    TopologyResource(TopologyService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "How this target is arranged and what it supports",
            description =
                    "Capabilities are asked of the server itself rather than inferred from its"
                            + " version, because the RESP forks report a version they are merely"
                            + " compatible with.")
    @APIResponse(responseCode = "200", description = "Arrangement and capabilities")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "connectionId")
    public Uni<TargetTopology> topology(@PathParam("connectionId") Long connectionId) {
        return service.describe(connectionId);
    }
}
