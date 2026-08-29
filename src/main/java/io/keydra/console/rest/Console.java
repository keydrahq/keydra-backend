package io.keydra.console.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.console.dto.HistoryEntry;
import io.keydra.console.service.ConsoleService;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Everything about a console session that is not the session itself.
 *
 * <p>Commands go over the WebSocket at the same path; history and policy are ordinary requests
 * because a page opening the console needs them once, before the socket is up.
 */
@Path("/api/v1/connections/{connectionId}/console")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Console", description = "Interactive command sessions")
@RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
public class Console {

    private final ConsoleService service;

    @Inject
    Console(ConsoleService service) {
        this.service = service;
    }

    @GET
    @Path("/history")
    @Operation(
            summary = "Command lines you have previously run against this target",
            description = "Most recent first, which is the order an up arrow walks.")
    @APIResponse(responseCode = "200", description = "Previously executed command lines")
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<List<HistoryEntry>> history(@PathParam("connectionId") Long connectionId) {
        return service.history(connectionId);
    }

    @DELETE
    @Path("/history")
    @Operation(summary = "Forget your own command history on this target")
    @APIResponse(responseCode = "204", description = "History cleared")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("console.history.clear")
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<Void> clearHistory(@PathParam("connectionId") Long connectionId) {
        return service.clearHistory(connectionId).replaceWithVoid();
    }

    @GET
    @Path("/denied-commands")
    @Operation(
            summary = "Commands the console refuses to run on this target",
            description =
                    "Returned so a client can refuse a command before the round trip, and explain"
                        + " why, rather than presenting the refusal as a server error. Answered for"
                        + " the target named in the path: what a profile allows is its own, and a"
                        + " console that greyed out the instance's list would be right somewhere"
                        + " else.")
    @APIResponse(responseCode = "200", description = "Refused command names, lower case")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.CONSOLE_RUN, connection = "connectionId")
    public Uni<List<String>> deniedCommands(@PathParam("connectionId") Long connectionId) {
        return service.deniedCommands(connectionId);
    }
}
