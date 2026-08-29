package io.keydra.engine;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A client attached to the store.
 *
 * @param id the store's identifier for the connection, which is what a kill names
 * @param address where it is connected from
 * @param name the name the client gave itself, when it gave one
 * @param ageSeconds how long it has been connected
 * @param idleSeconds how long since it last did anything
 * @param database the database index it has selected
 * @param lastCommand the last command it ran
 */
@Schema(name = "ClientConnection", description = "A client attached to the server")
public record ClientConnection(
        String id,
        String address,
        String name,
        Long ageSeconds,
        Long idleSeconds,
        Integer database,
        String lastCommand) {}
