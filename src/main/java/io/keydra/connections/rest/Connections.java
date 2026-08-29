package io.keydra.connections.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.connections.dto.ConnectionRequest;
import io.keydra.connections.dto.ConnectionResponse;
import io.keydra.connections.dto.ConnectionStatus;
import io.keydra.connections.mapper.ConnectionMapper;
import io.keydra.connections.service.ConnectionService;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * HTTP surface for saved targets.
 *
 * <p>Transport concerns only — paths, status codes and delegation. Business rules live in {@link
 * ConnectionService} and entity/DTO translation in {@link ConnectionMapper}.
 *
 * <p>Every method returns a {@link Uni}: the whole stack from HTTP through persistence to Redis is
 * non-blocking, so no endpoint ever occupies a worker thread waiting on I/O.
 */
@Path("/api/v1/connections")
@Produces(MediaType.APPLICATION_JSON)
// @Consumes sits on the methods that actually take a body: a caller should not
// have to send a Content-Type on the bodiless probe endpoint.
@Tag(name = "Connections", description = "Saved Redis/Valkey targets")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Connections {

    private final ConnectionService service;

    @Inject
    Connections(ConnectionService service) {
        this.service = service;
    }

    @GET
    @Operation(summary = "List all connection profiles with their last known status")
    @APIResponse(responseCode = "200", description = "Connection profiles")
    public Uni<List<ConnectionResponse>> list() {
        return service.list();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get one connection profile")
    @APIResponse(responseCode = "200", description = "Connection profile")
    @APIResponse(responseCode = "404", description = "No profile with that id")
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "id")
    public Uni<ConnectionResponse> get(@PathParam("id") Long id) {
        return service.get(id);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a connection profile")
    @APIResponse(responseCode = "201", description = "Created")
    @APIResponse(responseCode = "409", description = "Name already in use")
    @RolesAllowed(Roles.ADMIN)
    @Audited("connection.create")
    @RequiresPermission(Permission.CONNECTION_CREATE)
    public Uni<RestResponse<ConnectionResponse>> create(@Valid ConnectionRequest request) {
        return service.create(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update a connection profile")
    @APIResponse(responseCode = "200", description = "Updated")
    @APIResponse(responseCode = "404", description = "No profile with that id")
    @APIResponse(responseCode = "409", description = "Name already in use")
    @RolesAllowed(Roles.ADMIN)
    @Audited("connection.update")
    @RequiresPermission(value = Permission.CONNECTION_EDIT, connection = "id")
    public Uni<ConnectionResponse> update(
            @PathParam("id") Long id, @Valid ConnectionRequest request) {
        return service.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a connection profile")
    @APIResponse(responseCode = "204", description = "Deleted")
    @APIResponse(responseCode = "404", description = "No profile with that id")
    @RolesAllowed(Roles.ADMIN)
    @Audited("connection.delete")
    @RequiresPermission(value = Permission.CONNECTION_DELETE, connection = "id")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Probe a profile that has not been saved",
            description =
                    "The same probe as the one on a saved profile, against what the form"
                            + " currently says, and recording nothing. An edit sends no password"
                            + " it did not change, so pass the id being edited and the stored one"
                            + " is used.")
    @APIResponse(responseCode = "200", description = "Probe result")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("connection.test")
    @RequiresPermission(Permission.CONNECTION_CREATE)
    public Uni<ConnectionStatus> testDraft(
            @QueryParam("id") Long id, @Valid ConnectionRequest request) {
        return service.testDraft(id, request);
    }

    @POST
    @Path("/{id}/test")
    @Operation(
            summary = "Probe a saved profile and record the result",
            description =
                    "Connects, runs INFO server and returns the detected flavor and version. The"
                            + " result also updates the profile's tracked status.")
    @APIResponse(responseCode = "200", description = "Probe result")
    @APIResponse(responseCode = "404", description = "No profile with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("connection.test")
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "id")
    public Uni<ConnectionStatus> test(@PathParam("id") Long id) {
        return service.test(id);
    }
}
