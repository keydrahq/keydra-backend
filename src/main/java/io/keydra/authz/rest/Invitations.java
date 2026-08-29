package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.InvitationIssued;
import io.keydra.authz.entity.AccountInvitation;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.security.Principal;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Links that let somebody set their own password.
 *
 * <p>Two audiences in one resource, and the split is by method rather than by class because they
 * are the same feature: an administrator asks for a link, and whoever holds the link redeems it.
 * The second half is open by necessity — the person following it has no account to authenticate
 * with yet, which is the entire point.
 *
 * <p>Its own path rather than a branch of {@code /auth}. JAX-RS picks the longest matching resource
 * class and then looks for a method inside it, so anything under a path another class already
 * claims is a 404 with no explanation.
 *
 * <p>The token is what protects the open half. It is 256 random bits, it is stored only as a hash,
 * it expires, and it works once.
 */
@Path("/api/v1/invitations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Invitations", description = "Letting somebody set their own password")
public class Invitations {

    /** What the page behind a link is told before it offers a password form. */
    @Schema(name = "InvitationStanding", description = "Whether a link can still be used")
    public record StandingResponse(
            boolean usable,
            @Schema(description = "UNKNOWN, EXPIRED or USED when it cannot") String refusal,
            String username,
            String displayName,
            @Schema(description = "INVITATION or RESET, which decides the wording")
                    String purpose) {}

    /** Choosing a password with a link in hand. */
    @Schema(name = "AcceptInvitation", description = "The password somebody chose")
    public record AcceptRequest(
            @NotBlank
                    @Size(
                            min = 12,
                            message = "A password shorter than twelve characters is a guess away")
                    String password) {}

    /** Asking for a link because a password has been forgotten. */
    @Schema(name = "ResetRequest", description = "Who has forgotten their password")
    public record ResetRequest(@NotBlank String username) {}

    private final io.keydra.authz.service.Invitations invitations;
    private final io.keydra.authz.service.PublicUrl publicUrl;

    @Inject
    Invitations(
            io.keydra.authz.service.Invitations invitations,
            io.keydra.authz.service.PublicUrl publicUrl) {
        this.invitations = invitations;
        this.publicUrl = publicUrl;
    }

    // --- What an administrator does ----------------------------------------

    @POST
    @Path("/for-user/{id}")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.USERS_MANAGE)
    @Audited("user.invite")
    @Operation(
            summary = "Send somebody a link to set their own password",
            description =
                    "Ends any link the account already had. The new one is mailed when this"
                            + " instance can send mail and the account has an address; either way"
                            + " it comes back here, so it can be passed on by hand.")
    @APIResponse(responseCode = "201", description = "A link was made")
    @APIResponse(responseCode = "404", description = "No such account")
    public Uni<RestResponse<InvitationIssued>> invite(
            @PathParam("id") Long id,
            @jakarta.ws.rs.core.Context jakarta.ws.rs.core.SecurityContext caller) {
        return invitations
                .invite(id, AccountInvitation.Purpose.INVITATION, nameOf(caller))
                .map(
                        issued ->
                                RestResponse.status(
                                        Status.CREATED,
                                        new InvitationIssued(
                                                issued.mailed(),
                                                issued.address(),
                                                publicUrl
                                                        .absolute("/invitation/" + issued.token())
                                                        .orElse("/invitation/" + issued.token()))))
                .onFailure(IllegalArgumentException.class)
                .recoverWithItem(() -> RestResponse.status(Status.NOT_FOUND));
    }

    // --- What anybody holding a link does ----------------------------------

    @GET
    @Path("/{token}")
    @PermitAll
    @Operation(
            summary = "Whether a link can still be used",
            description =
                    "Asked before a password form is shown, so an old link says which of the three"
                            + " things happened rather than refusing after somebody has chosen a"
                            + " password.")
    @APIResponse(responseCode = "200", description = "What the link is worth")
    public Uni<StandingResponse> standing(@PathParam("token") String token) {
        return invitations.standing(token).map(Invitations::toResponse);
    }

    @POST
    @Path("/{token}")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Set a password with a link",
            description = "Spends the link. A second attempt with the same one is refused.")
    @APIResponse(responseCode = "200", description = "The password was set")
    @APIResponse(responseCode = "410", description = "The link was expired, used or unknown")
    public Uni<RestResponse<StandingResponse>> accept(
            @PathParam("token") String token, @Valid AcceptRequest request) {
        return invitations
                .accept(token, request.password())
                .map(
                        standing ->
                                standing.usable()
                                        ? RestResponse.ok(toResponse(standing))
                                        : RestResponse.status(Status.GONE, toResponse(standing)));
    }

    @POST
    @Path("/forgotten")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Ask for a link because a password has been forgotten",
            description =
                    "Answers the same way whether or not there is such an account. Anything else"
                            + " would be a way to ask Keydra who has an account here.")
    @APIResponse(responseCode = "202", description = "If there is such an account, a link was sent")
    public Uni<RestResponse<Void>> forgotten(@Valid ResetRequest request) {
        return invitations
                .requestReset(request.username())
                .replaceWith(RestResponse.status(Status.ACCEPTED));
    }

    private static StandingResponse toResponse(
            io.keydra.authz.service.Invitations.Standing standing) {
        return new StandingResponse(
                standing.usable(),
                standing.refusal() == null ? null : standing.refusal().name(),
                standing.username(),
                standing.displayName(),
                standing.purpose() == null ? null : standing.purpose().name());
    }

    private static String nameOf(jakarta.ws.rs.core.SecurityContext caller) {
        Principal principal = caller == null ? null : caller.getUserPrincipal();
        return principal == null ? null : principal.getName();
    }
}
