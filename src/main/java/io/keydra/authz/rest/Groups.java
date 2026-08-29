package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.GroupRequest;
import io.keydra.authz.dto.AuthzDtos.GroupSummary;
import io.keydra.authz.dto.AuthzDtos.MembershipRequest;
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
 * Named sets of people, which may contain other sets.
 *
 * <p>Nesting is what keeps the grants table small: "the platform team may operate the payments
 * servers" is one row however many people are on the team, and stays one row when somebody joins.
 *
 * <p>A nesting that would put a group inside itself is refused, because resolution walks this graph
 * and a cycle makes "who is in this group" a question with no answer.
 */
@Path("/api/v1/authz/groups")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Groups", description = "Named sets of people")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.GROUPS_MANAGE)
public class Groups {

    private final AuthzAdminService service;

    @Inject
    Groups(AuthzAdminService service) {
        this.service = service;
    }

    @GET
    @Operation(summary = "Every group, with what is directly inside it")
    @APIResponse(responseCode = "200", description = "Groups")
    public Uni<List<GroupSummary>> list() {
        return service.groups();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a group")
    @APIResponse(responseCode = "201", description = "Created")
    @Audited("group.create")
    public Uni<RestResponse<GroupSummary>> create(@Valid GroupRequest request) {
        return service.createGroup(request)
                .map(created -> RestResponse.status(Status.CREATED, created));
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a group",
            description = "Its memberships and the grants made to it go with it.")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("group.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.deleteGroup(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/{id}/members")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Put a person, or another group, into this group",
            description = "One or the other, never both — a membership names a single member.")
    @APIResponse(responseCode = "204", description = "Added")
    @APIResponse(responseCode = "409", description = "That would put the group inside itself")
    @Audited("group.member.add")
    public Uni<RestResponse<Void>> addMember(
            @PathParam("id") Long id, @Valid MembershipRequest request) {
        return service.addMember(id, request).map(ignored -> RestResponse.noContent());
    }

    @DELETE
    @Path("/members/{membershipId}")
    @Operation(summary = "Take something out of a group")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("group.member.remove")
    public Uni<RestResponse<Void>> removeMember(@PathParam("membershipId") Long membershipId) {
        return service.removeMember(membershipId).map(ignored -> RestResponse.noContent());
    }
}
