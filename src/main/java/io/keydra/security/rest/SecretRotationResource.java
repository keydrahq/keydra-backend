package io.keydra.security.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.keydra.security.dto.SecretRotationDtos.RotationResult;
import io.keydra.security.dto.SecretRotationDtos.RotationStatus;
import io.keydra.security.service.SecretRotation;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The key that protects every stored credential, and moving off it.
 *
 * <p>A key that cannot be rotated is a key nobody rotates, which after the first person leaves is
 * the same as not having one. The whole procedure is three steps and the instance stays up for all
 * of them: add the new key beside the old one and restart, ask this to move everything, then take
 * the old key out and restart again. The last restart is the proof — if anything had been missed,
 * it would no longer decrypt.
 */
@Path("/api/v1/security/encryption")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Encryption", description = "The key that protects every stored credential")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.CRYPTO_ROTATE)
public class SecretRotationResource {

    private final SecretRotation rotation;

    @Inject
    SecretRotationResource(SecretRotation rotation) {
        this.rotation = rotation;
    }

    @GET
    @Operation(
            summary = "Which key the stored secrets are under",
            description =
                    "The id of the key that writes, and how many stored values were written by it"
                            + " rather than by one that is now only read.")
    @APIResponse(responseCode = "200", description = "The state of the stored secrets")
    public Uni<RotationStatus> status() {
        return rotation.status();
    }

    @POST
    @Path("/rotate")
    @Operation(
            summary = "Re-encrypt everything with the key that writes now",
            description =
                    "Every stored credential is read with whichever key wrote it and written back"
                            + " with the current one. Once this reports nothing left elsewhere, the"
                            + " old key can be removed from the configuration.")
    @APIResponse(responseCode = "200", description = "How many values were moved")
    @Audited("crypto.rotate")
    public Uni<RotationResult> rotate() {
        return rotation.rotate();
    }
}
