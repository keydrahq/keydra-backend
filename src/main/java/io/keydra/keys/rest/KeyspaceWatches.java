package io.keydra.keys.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.keys.dto.KeyspaceWatchState;
import io.keydra.keys.service.KeyspaceWatch;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Listening to what a target says about its own changes.
 *
 * <p>Its own resource rather than another path under {@code /keys} because the three things here
 * are not key operations: they are about whether anybody is listening to this target and whether it
 * is speaking. The changes themselves never come back through here — they go out over the
 * notification hub as {@code KeysChanged}, which is what every page watching keys has listened for
 * since phase 2.
 */
@Path("/api/v1/connections/{connectionId}/keyspace-watch")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Keys", description = "Browse and mutate keys on a target")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class KeyspaceWatches {

    private final KeyspaceWatch watch;

    @Inject
    KeyspaceWatches(KeyspaceWatch watch) {
        this.watch = watch;
    }

    @GET
    @Operation(
            summary = "Whether this target's changes are being heard",
            description =
                    "Says whether the store announces its changes at all, whether this server is"
                        + " currently set to, and whether Keydra is listening. Takes no lease, so"
                        + " asking does not start a watch.")
    @APIResponse(responseCode = "200", description = "The state of the watch")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<KeyspaceWatchState> state(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") @DefaultValue("0") int database) {
        return watch.state(connectionId, database);
    }

    @POST
    @Operation(
            summary = "Take or renew a lease on this target's changes",
            description =
                    "Opens the watch if nobody was holding one and returns a lease. Send the lease"
                        + " back on the next call to renew it; a lease nobody renews lapses and the"
                        + " watch closes with the last of them, so a browser that vanishes does not"
                        + " hold a connection open.")
    @APIResponse(responseCode = "200", description = "The lease, and the state of the watch")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<KeyspaceWatchState> hold(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") @DefaultValue("0") int database,
            @QueryParam("lease") String leaseId) {
        return watch.hold(connectionId, database, leaseId);
    }

    @DELETE
    @Operation(
            summary = "Give a lease back",
            description =
                    "Closes the watch when it was the last lease on it. Not required — a lease"
                            + " lapses on its own — but a page that knows it is leaving should say"
                            + " so rather than leave a connection open until it does.")
    @APIResponse(responseCode = "204", description = "The lease is no longer held")
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<Void> release(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") @DefaultValue("0") int database,
            @QueryParam("lease") String leaseId) {
        // Uni rather than void although nothing here waits on anything: every method on this
        // surface returns one, and a single synchronous signature is a thing somebody later reads
        // as permission to do blocking work in the next one.
        watch.release(connectionId, database, leaseId);
        return Uni.createFrom().voidItem();
    }

    @POST
    @Path("/announce")
    @Operation(
            summary = "Ask the target to announce its changes",
            description =
                    "Adds the keyspace-notification flags to whatever the server's setting already"
                            + " says — a union, never a replacement, so a server already announcing"
                            + " something goes on announcing it. Changing a running server's"
                            + " configuration, which is why this needs more than reading keys.")
    @APIResponse(responseCode = "200", description = "The state of the watch, after the change")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<KeyspaceWatchState> announce(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") @DefaultValue("0") int database) {
        return watch.announce(connectionId, database);
    }
}
