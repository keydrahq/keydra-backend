package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.UserRequest;
import io.keydra.authz.dto.AuthzDtos.UserSummary;
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
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The people Keydra knows.
 *
 * <p>A password may be sent here and is never sent back — not as a hash, not as a length, not as
 * anything but whether one is set. The only things this resource can say about a password are
 * "there is one" and "there is not".
 */
@Path("/api/v1/authz/users")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Users", description = "The people Keydra knows")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.USERS_MANAGE)
public class Users {

    private final AuthzAdminService service;

    @Inject
    Users(AuthzAdminService service) {
        this.service = service;
    }

    @GET
    @Operation(summary = "Everybody, with the groups they are directly in")
    @APIResponse(responseCode = "200", description = "The people Keydra knows")
    public Uni<List<UserSummary>> list() {
        return service.users();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Create a local account",
            description =
                    "Without a password the account exists but cannot be signed into, which is how"
                            + " somebody is prepared before their password is set.")
    @APIResponse(responseCode = "201", description = "Created")
    @APIResponse(responseCode = "409", description = "That username is taken")
    @Audited("user.create")
    public Uni<RestResponse<UserSummary>> create(@Valid UserRequest request) {
        return service.createUser(request)
                .map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change an account",
            description =
                    "An absent password leaves the stored one alone. The API never returns one, so"
                            + " an edit form arrives with that field empty and clearing it on every"
                            + " edit would lock people out.")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "No such user")
    @Audited("user.update")
    public Uni<UserSummary> update(@PathParam("id") Long id, @Valid UserRequest request) {
        return service.updateUser(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove an account",
            description =
                    "Their memberships and grants go with them, so a later account cannot inherit"
                            + " powers from an id that was reused.")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("user.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.deleteUser(id).map(ignored -> RestResponse.noContent());
    }
}
