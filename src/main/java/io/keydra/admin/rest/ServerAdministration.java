package io.keydra.admin.rest;

import io.keydra.admin.dto.SettingChange;
import io.keydra.admin.exception.AdminUnsupportedException;
import io.keydra.admin.exception.SettingRefusedException;
import io.keydra.admin.service.ServerAdminService;
import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.engine.PersistenceState;
import io.keydra.engine.ServerSetting;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * How a target is configured, and how it keeps its data.
 *
 * <p>Reading is open to anyone who may see the target; changing anything is not. A setting changed
 * badly can evict keys nobody asked to expire, and a snapshot started at the wrong moment can stall
 * a busy instance — so every write here is an operator's act and every one is audited.
 */
@Path("/api/v1/connections/{connectionId}/admin")
@Produces(MediaType.APPLICATION_JSON)
public class ServerAdministration {

    private final ServerAdminService admin;

    @Inject
    ServerAdministration(ServerAdminService admin) {
        this.admin = admin;
    }

    @GET
    @Path("/settings")
    @Operation(
            summary = "Everything this target is configured to do",
            description =
                    "The running configuration. Values that are secrets rather than settings —"
                            + " requirepass and its relatives — are reported as set rather than"
                            + " returned.")
    @APIResponse(responseCode = "200", description = "The settings")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.SERVER_READ, connection = "connectionId")
    public Uni<List<ServerSetting>> settings(
            @PathParam("connectionId") Long connectionId, @QueryParam("match") String glob) {
        return admin.settings(connectionId, glob);
    }

    @POST
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change one setting while the server runs",
            description =
                    "Takes effect immediately and is forgotten on restart. Making it permanent is"
                            + " a separate act, because a configuration file rewritten badly is a"
                            + " server that will not start.")
    @APIResponse(responseCode = "204", description = "The setting was changed")
    @APIResponse(responseCode = "400", description = "The server refused the value")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("server.config.set")
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Response> change(
            @PathParam("connectionId") Long connectionId, @Valid SettingChange change) {
        return admin.change(connectionId, change).replaceWith(Response.noContent().build());
    }

    @POST
    @Path("/settings/persist")
    @Operation(
            summary = "Write the running configuration back to the server's own file",
            description = "So that what is running now is what starts next time.")
    @APIResponse(responseCode = "204", description = "The file was rewritten")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("server.config.persist")
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Response> persist(@PathParam("connectionId") Long connectionId) {
        return admin.persistSettings(connectionId).replaceWith(Response.noContent().build());
    }

    @GET
    @Path("/persistence")
    @Operation(
            summary = "How this target is keeping its data",
            description =
                    "The two figures worth acting on are the last successful save and whether the"
                        + " last attempt failed: a server that has been unable to write for a day"
                        + " is one restart away from losing everything since, and it says so"
                        + " nowhere a person would look.")
    @APIResponse(responseCode = "200", description = "The state")
    @RequiresPermission(value = Permission.SERVER_READ, connection = "connectionId")
    public Uni<PersistenceState> persistence(@PathParam("connectionId") Long connectionId) {
        return admin.persistence(connectionId);
    }

    @POST
    @Path("/persistence/snapshot")
    @Operation(
            summary = "Write a snapshot in the background",
            description =
                    "In the background because the foreground form blocks every client until it"
                        + " finishes, which on a large instance is long enough to time out whatever"
                        + " was using it.")
    @APIResponse(responseCode = "202", description = "The server accepted the request")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("server.snapshot")
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Response> snapshot(@PathParam("connectionId") Long connectionId) {
        return admin.snapshot(connectionId)
                .replaceWith(Response.status(Response.Status.ACCEPTED).build());
    }

    @POST
    @Path("/persistence/rewrite")
    @Operation(summary = "Rewrite the append-only log, compacting it")
    @APIResponse(responseCode = "202", description = "The server accepted the request")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("server.log.rewrite")
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Response> rewrite(@PathParam("connectionId") Long connectionId) {
        return admin.rewriteLog(connectionId)
                .replaceWith(Response.status(Response.Status.ACCEPTED).build());
    }

    /** The server turning a value down is the caller's business, so it is a 400. */
    @ServerExceptionMapper
    public Response refused(SettingRefusedException refusal) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(refusal.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }

    /** A store whose settings are fixed at startup is a 501 rather than a failure. */
    @ServerExceptionMapper
    public Response unsupported(AdminUnsupportedException unsupported) {
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(unsupported.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
