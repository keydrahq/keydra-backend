package io.keydra.security.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire shapes for rotating the key that protects every stored credential. */
public final class SecretRotationDtos {

    private SecretRotationDtos() {}

    /**
     * What is encrypted, and with which key.
     *
     * @param currentKeyId the id the key that writes stamps on what it writes
     * @param onCurrentKey values already written by it
     * @param onOtherKeys values written by a key that is now only read — what a rotation moves
     */
    @Schema(name = "SecretRotationStatus", description = "Which key the stored secrets are under")
    public record RotationStatus(String currentKeyId, long onCurrentKey, long onOtherKeys) {}

    /** What a rotation did. */
    @Schema(name = "SecretRotationResult", description = "What a rotation moved")
    public record RotationResult(String currentKeyId, long rotated) {}
}
