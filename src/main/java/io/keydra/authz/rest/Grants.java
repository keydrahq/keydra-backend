package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.GrantRequest;
import io.keydra.authz.dto.AuthzDtos.GrantSummary;
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
 * The sentences: this subject holds this role on this scope.
 *
 * <p>The whole model is this one table plus the two containments. There are no denials — a grant
 * adds and nothing subtracts — because a rule that takes something away is a rule you cannot find
 * by looking at what somebody has. Absence is the denial, and absence is visible.
 */
@Path("/api/v1/authz/grants")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Grants", description = "Who holds which role on what")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.GRANTS_MANAGE)
public class Grants {

    private final AuthzAdminService service;

    @Inject
    Grants(AuthzAdminService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Every grant, with names beside the ids",
            description =
                    "A grants page is read rather than joined: a row has to say"
                            + " \"payments-devs / operator / payments-servers\" rather than three"
                            + " numbers.")
    @APIResponse(responseCode = "200", description = "Grants")
    public Uni<List<GrantSummary>> list() {
        return service.grants();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Grant a role on a scope",
            description =
                    "A grant over Keydra itself names no scope; one over anything else must name"
                            + " one.")
    @APIResponse(responseCode = "201", description = "Granted")
    @APIResponse(responseCode = "409", description = "The scope and the scope type disagree")
    @Audited("grant.create")
    public Uni<RestResponse<GrantSummary>> grant(@Valid GrantRequest request) {
        return service.grant(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Take a grant back")
    @APIResponse(responseCode = "204", description = "Revoked")
    @Audited("grant.delete")
    public Uni<RestResponse<Void>> revoke(@PathParam("id") Long id) {
        return service.revoke(id).map(ignored -> RestResponse.noContent());
    }
}
