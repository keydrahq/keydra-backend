package io.keydra.tunnels.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire shapes for jump hosts. */
public final class TunnelDtos {

    private TunnelDtos() {}

    /**
     * A tunnel as the list shows it. No secret of any kind.
     *
     * @param usedBy how many targets and destinations reach through it, so removing one can say
     *     what it would strand
     * @param verifiesHostKey whether a fingerprint is pinned. False is the loud one: everything
     *     Keydra holds for everything behind this jump host travels through it
     */
    @Schema(name = "TunnelSummary", description = "A jump host things are reached through")
    public record TunnelSummary(
            Long id,
            String name,
            String host,
            int port,
            String username,
            boolean hasPassword,
            boolean hasPrivateKey,
            boolean verifiesHostKey,
            String hostKeyFingerprint,
            String describedAs,
            long usedBy) {}

    /**
     * A tunnel to create or change.
     *
     * <p>An absent secret leaves the stored one alone; an empty one clears it. The same rule as a
     * target's password, and for the same reason: nothing can read a secret back to prefill it.
     */
    @Schema(name = "TunnelRequest", description = "A jump host to create or change")
    public record TunnelRequest(
            @NotBlank String name,
            @NotBlank String host,
            @Min(1) @Max(65535) int port,
            @NotBlank String username,
            @Schema(description = "Write-only; never returned") String password,
            @Schema(description = "Write-only; never returned") String privateKey,
            @Schema(description = "Write-only; never returned") String passphrase,
            @Schema(description = "SHA256:… the jump host must present. Empty accepts any key.")
                    String hostKeyFingerprint) {}

    /**
     * What a "test this tunnel" attempt found.
     *
     * @param fingerprint the key the jump host presented, so pinning it is a copy and a save rather
     *     than a trip to a terminal
     */
    @Schema(name = "TunnelCheck", description = "Whether a jump host works")
    public record TunnelCheck(boolean reachable, String message, String fingerprint) {}
}
