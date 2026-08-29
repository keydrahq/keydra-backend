package io.keydra.authz.rest;

import io.keydra.authz.dto.AuthzDtos.RecoveryCodes;
import io.keydra.authz.dto.AuthzDtos.SecondFactorConfirmation;
import io.keydra.authz.dto.AuthzDtos.SecondFactorSetup;
import io.keydra.authz.dto.AuthzDtos.SecondFactorState;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.authz.service.SecondFactors;
import io.keydra.authz.service.Totp;
import io.keydra.common.rest.ApiError;
import io.keydra.security.Audited;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Your own second factor, and nobody else's.
 *
 * <p>The same decision as sessions and preferences: managing your own is part of being signed in
 * rather than something to be granted, and there is no endpoint that names an account. An
 * administrator who could turn somebody's factor off would be a support channel that is also a way
 * around the factor.
 *
 * <p>{@code @Authenticated} rather than {@code @PermitAll}, unlike preferences — a second factor is
 * about an account, and on an instance with no accounts there is nothing here to manage.
 */
@Path("/api/v1/auth/second-factor")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Second factor", description = "The authenticator paired with your account")
@Authenticated
public class SecondFactorResource {

    private final SecondFactors factors;
    private final CallerPermissions caller;
    private final SecurityIdentity identity;
    private final String issuer;

    @Inject
    SecondFactorResource(
            SecondFactors factors,
            CallerPermissions caller,
            SecurityIdentity identity,
            @ConfigProperty(name = "quarkus.application.name", defaultValue = "Keydra")
                    String issuer) {
        this.factors = factors;
        this.caller = caller;
        this.identity = identity;
        this.issuer = issuer;
    }

    @GET
    @Operation(summary = "Whether you have a second factor, and how many recovery codes are left")
    @APIResponse(responseCode = "200", description = "Where you stand")
    public Uni<SecondFactorState> state() {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                factors.isRequiredFor(userId)
                                        .flatMap(
                                                enabled ->
                                                        factors.recoveryCodesLeft(userId)
                                                                .map(
                                                                        left ->
                                                                                new SecondFactorState(
                                                                                        enabled,
                                                                                        left))));
    }

    /**
     * Begins a pairing and answers the secret, once.
     *
     * <p>Nothing is enforced yet. A secret that is never confirmed is an attempt somebody
     * abandoned, and the next attempt replaces it — which is what stops the page from being a way
     * to lock yourself out by closing a tab.
     */
    @POST
    @Operation(
            summary = "Begin pairing an authenticator",
            description = "The secret is answered once and never again. Confirm it to turn it on.")
    @APIResponse(responseCode = "200", description = "The secret and the enrolment URI")
    @Audited("second-factor.begin")
    public Uni<SecondFactorSetup> begin() {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                factors.begin(userId)
                                        .map(
                                                secret ->
                                                        new SecondFactorSetup(
                                                                secret,
                                                                Totp.enrolmentUri(
                                                                        issuer,
                                                                        identity.getPrincipal()
                                                                                .getName(),
                                                                        secret))));
    }

    /**
     * Proves the pairing and turns it on, answering the recovery codes.
     *
     * <p>Shown once. Nothing here can show them again, which is the point of storing only hashes —
     * and is why the page that receives them says so before anybody navigates away.
     */
    @POST
    @Path("/confirm")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Prove the pairing with one code",
            description = "Turns the factor on and answers the recovery codes, once.")
    @APIResponse(responseCode = "200", description = "The recovery codes")
    @APIResponse(responseCode = "409", description = "That was not the code")
    @Audited("second-factor.confirm")
    public Uni<RestResponse<?>> confirm(@Valid SecondFactorConfirmation confirmation) {
        return caller.currentUserId()
                .flatMap(userId -> factors.confirm(userId, confirmation.code(), Instant.now()))
                .map(
                        codes ->
                                codes.isEmpty()
                                        ? RestResponse.status(
                                                RestResponse.Status.CONFLICT,
                                                new ApiError(
                                                        "That was not the code the authenticator is"
                                                            + " showing. Check the time on the"
                                                            + " device and try the current one."))
                                        : RestResponse.ok(new RecoveryCodes(codes)));
    }

    @POST
    @Path("/recovery-codes")
    @Operation(
            summary = "Replace your recovery codes",
            description = "Every code from the previous set stops working.")
    @APIResponse(responseCode = "200", description = "The new codes")
    @APIResponse(responseCode = "409", description = "There is no second factor to have codes for")
    @Audited("second-factor.recovery-codes")
    public Uni<RestResponse<?>> regenerate() {
        return caller.currentUserId()
                .flatMap(factors::regenerateRecoveryCodes)
                .map(
                        codes ->
                                codes.isEmpty()
                                        ? RestResponse.status(
                                                RestResponse.Status.CONFLICT,
                                                new ApiError(
                                                        "There is no second factor on this account,"
                                                            + " so there are no recovery codes to"
                                                            + " replace."))
                                        : RestResponse.ok(new RecoveryCodes(codes)));
    }

    @DELETE
    @Operation(
            summary = "Turn off your second factor",
            description = "The pairing and every recovery code are forgotten.")
    @APIResponse(responseCode = "200", description = "Whether there was one to turn off")
    @Audited("second-factor.disable")
    public Uni<Boolean> disable() {
        return caller.currentUserId().flatMap(factors::disable);
    }
}
