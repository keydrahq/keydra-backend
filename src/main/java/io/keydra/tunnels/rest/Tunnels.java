package io.keydra.tunnels.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.keydra.tunnels.dto.TunnelDtos.TunnelCheck;
import io.keydra.tunnels.dto.TunnelDtos.TunnelRequest;
import io.keydra.tunnels.dto.TunnelDtos.TunnelSummary;
import io.keydra.tunnels.service.TunnelService;
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
 * The jump hosts, as rows.
 *
 * <p>An administrator's job, and deliberately not an operator's: a jump host carries a credential
 * that reaches a whole network, and everything Keydra holds for everything behind it travels
 * through it. Choosing which tunnel a target uses is part of editing that target; describing the
 * tunnel is this.
 *
 * <p>A secret may be sent here and is never sent back.
 */
@Path("/api/v1/tunnels")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Tunnels", description = "Jump hosts things are reached through")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.TUNNEL_MANAGE)
public class Tunnels {

    private final TunnelService service;

    @Inject
    Tunnels(TunnelService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Every configured tunnel",
            description =
                    "With how many targets and destinations reach through each, which is what"
                            + " removing one has to be able to say.")
    @APIResponse(responseCode = "200", description = "The tunnels")
    public Uni<List<TunnelSummary>> list() {
        return service.list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a tunnel")
    @APIResponse(responseCode = "201", description = "Added")
    @APIResponse(responseCode = "409", description = "That name is taken, or it has no credential")
    @Audited("tunnel.create")
    public Uni<RestResponse<TunnelSummary>> create(@Valid TunnelRequest request) {
        return service.create(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change a tunnel",
            description =
                    "An absent secret leaves the stored one alone. The open session is dropped, so"
                        + " everything behind this jump host reconnects through what it says now.")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "No such tunnel, or it has no credential")
    @Audited("tunnel.update")
    public Uni<TunnelSummary> update(@PathParam("id") Long id, @Valid TunnelRequest request) {
        return service.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a tunnel",
            description =
                    "Refused while anything still reaches through it: the alternative is targets"
                            + " quietly trying to connect directly to an address that is not"
                            + " reachable, which looks like a server being down.")
    @APIResponse(responseCode = "204", description = "Removed")
    @APIResponse(responseCode = "409", description = "Something still reaches through it")
    @Audited("tunnel.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/check")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Try a jump host that has not been saved",
            description =
                    "The same attempt as the check on a saved tunnel, against what the form"
                            + " currently says. Nothing is stored — the point is to find out that"
                            + " a key is wrong while somebody is still looking at the form. An"
                            + " edit sends no secret it did not change, so pass the id being"
                            + " edited and the stored ones are used.")
    @APIResponse(responseCode = "200", description = "What the attempt found, working or not")
    @APIResponse(responseCode = "409", description = "The tunnel could not be tried as described")
    @Audited("tunnel.check")
    public Uni<TunnelCheck> checkDraft(@QueryParam("id") Long id, @Valid TunnelRequest request) {
        return service.check(id, request);
    }

    @POST
    @Path("/{id}/check")
    @Operation(
            summary = "Find out whether it works",
            description =
                    "Connects and authenticates, and answers the host key it was presented —"
                            + " whether or not the attempt worked. A tunnel that pins no key is one"
                            + " anything answering on that address can impersonate, and pinning one"
                            + " should be a copy and a save rather than a trip to a terminal.")
    @APIResponse(responseCode = "200", description = "What the attempt found, working or not")
    @Audited("tunnel.check")
    public Uni<TunnelCheck> check(@PathParam("id") Long id) {
        return service.check(id);
    }
}
