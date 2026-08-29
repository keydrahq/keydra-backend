package io.keydra.connections.dto;

import io.keydra.connections.entity.ConnectionType;
import io.keydra.connections.entity.ServerFlavor;
import io.keydra.engine.EngineType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Outbound contract for a saved connection profile.
 *
 * <p>There is deliberately no password field: the secret cannot be leaked by serialising this
 * record, only {@code hasPassword} tells the UI whether one is stored.
 */
@Schema(name = "ConnectionResponse", description = "Saved connection profile")
public record ConnectionResponse(
        Long id,
        String name,
        String host,
        int port,
        String username,
        @Schema(description = "Whether a password is stored; the value is never returned")
                boolean hasPassword,
        boolean tls,
        /**
         * The authority trusted for this target, as PEM, or null for the JVM's own store.
         *
         * <p>Returned, unlike the fields near it, because a certificate authority's certificate is
         * the public half of the thing: showing it is how somebody checks the right one is
         * configured.
         */
        String tlsCaCert,
        /** The certificate this instance presents to this target, if any. Public, like any. */
        String tlsClientCert,
        @Schema(description = "Whether a client key is stored; the value is never returned")
                boolean hasClientKey,
        @Schema(
                        description =
                                "Whether that key is opened with a stored passphrase; the value is"
                                        + " never returned")
                boolean hasClientKeyPassphrase,
        @Schema(
                        description =
                                "Whether an operation that could empty this target has to name it"
                                        + " first")
                boolean guarded,
        @Schema(
                        description =
                                "Whether an operation that could empty this target waits for a"
                                        + " second person")
                boolean requiresApproval,
        @Schema(
                        description =
                                "Commands the console may run here that it refuses elsewhere; empty"
                                        + " when this target refuses whatever the instance refuses")
                java.util.List<String> consoleAllowed,
        int database,
        EngineType engine,
        ServerFlavor flavor,
        ConnectionType type,
        String sentinelMasterName,
        String namespace,
        String notes,
        /**
         * The jump host this target is reached through, by id.
         *
         * <p>The id and not the name: whatever draws this already has the list of tunnels — it
         * needs one to offer the choice — so a name here would be a join on the server to save a
         * lookup the client can do without asking.
         */
        Long tunnelId,
        ConnectionStatus status) {}
