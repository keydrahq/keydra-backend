package io.keydra.connections.dto;

import io.keydra.connections.entity.ConnectionType;
import io.keydra.connections.entity.ServerFlavor;
import io.keydra.engine.EngineType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Inbound contract for creating or updating a connection profile.
 *
 * @param password write-only; {@code null} keeps the stored secret, {@code ""} clears it
 * @param tunnelId the jump host to reach this target through, or null for a direct connection
 */
@Schema(name = "ConnectionRequest", description = "Connection profile to create or update")
public record ConnectionRequest(
        @NotBlank String name,
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        String username,
        @Schema(description = "Write-only; never returned") String password,
        boolean tls,
        @Schema(
                        description =
                                "The authority to trust for this target, as PEM. Absent leaves the"
                                    + " stored one alone; empty clears it and the JVM's own store"
                                    + " applies again.")
                String tlsCaCert,
        @Schema(description = "The certificate to present when the target asks for one, as PEM")
                String tlsClientCert,
        @Schema(
                        description =
                                "Its private half, as PEM. Write-only; never returned. Absent"
                                        + " leaves the stored one alone, empty clears it.")
                String tlsClientKey,
        @Schema(
                        description =
                                "What opens that key, where it is protected. Write-only; never"
                                        + " returned. Absent leaves the stored one alone, empty"
                                        + " clears it.")
                String tlsClientKeyPassphrase,
        @Schema(
                        description =
                                "Whether an operation that could empty this target has to name it"
                                    + " first. Off by default and never inferred: a target is not"
                                    + " production because its name says so.")
                boolean guarded,
        @Schema(
                        description =
                                "Whether an operation that could empty this target waits for a"
                                    + " second person. Beside the naming rather than inside it: one"
                                    + " asks which server this is and the other asks whether it"
                                    + " should happen at all.")
                boolean requiresApproval,
        @Schema(
                        description =
                                "Commands the console may run on this target that it refuses"
                                    + " elsewhere. Only the commands refused because of what they"
                                    + " do to the target can be named; the ones refused because of"
                                    + " what they would do to Keydra's own connection are the same"
                                    + " everywhere. Absent leaves the stored list alone, empty"
                                    + " clears it.")
                java.util.List<String> consoleAllowed,
        @Min(0) int database,
        EngineType engine,
        ServerFlavor flavor,
        @NotNull ConnectionType type,
        String sentinelMasterName,
        String namespace,
        String notes,
        Long tunnelId) {}
