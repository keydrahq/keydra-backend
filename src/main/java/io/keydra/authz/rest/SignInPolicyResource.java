package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.SignInPolicyRequest;
import io.keydra.authz.dto.AuthzDtos.SignInPolicyState;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.authz.service.SignInPolicies;
import io.keydra.common.rest.ApiError;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What this installation asks of everybody who signs in.
 *
 * <p>Its own permission rather than an administrator's by definition: making accounts and setting
 * the terms every account signs in under are different acts. This one restricts every person at
 * once until they enrol, which is a thing worth granting deliberately.
 */
@Path("/api/v1/auth/policy")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Sign-in policy", description = "What this instance asks of whoever signs in")
public class SignInPolicyResource {

    private final SignInPolicies policies;
    private final CallerPermissions caller;

    @Inject
    SignInPolicyResource(SignInPolicies policies, CallerPermissions caller) {
        this.policies = policies;
        this.caller = caller;
    }

    @GET
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.POLICY_MANAGE)
    @Operation(
            summary = "What is asked of whoever signs in",
            description =
                    "Includes how many accounts the requirement reaches and have not enrolled,"
                            + " which is the number to know before turning it on.")
    @APIResponse(responseCode = "200", description = "The policy as it stands")
    public Uni<SignInPolicyState> state() {
        return policies.state();
    }

    @PUT
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.POLICY_MANAGE)
    @Audited("policy.second-factor")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Require a second factor, or stop requiring one",
            description =
                    "Turning it on is refused unless the caller has paired an authenticator with"
                            + " their own account: without that check, requiring a factor takes"
                            + " away the roles of whoever required it, including the permission to"
                            + " undo it.")
    @APIResponse(responseCode = "200", description = "The policy as it now stands")
    @APIResponse(
            responseCode = "409",
            description = "The caller has no second factor of their own",
            content =
                    @org.eclipse.microprofile.openapi.annotations.media.Content(
                            schema = @Schema(implementation = ApiError.class)))
    public Uni<SignInPolicyState> require(
            @Valid SignInPolicyRequest request, @jakarta.ws.rs.core.Context SecurityContext who) {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                policies.requireSecondFactor(
                                        request.secondFactorRequired(), userId, nameOf(who)));
    }

    private static String nameOf(SecurityContext who) {
        return who == null || who.getUserPrincipal() == null
                ? null
                : who.getUserPrincipal().getName();
    }
}
