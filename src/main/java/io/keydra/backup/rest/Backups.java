package io.keydra.backup.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.backup.dto.BackupDtos.BackupSummary;
import io.keydra.backup.dto.BackupDtos.BackupTaken;
import io.keydra.backup.dto.BackupDtos.RestoreRequest;
import io.keydra.backup.service.BackupService;
import io.keydra.keys.dto.ImportResult;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Taking a backup of one target and putting one back.
 *
 * <p>Under the connection, because every one of these is about a target and the permissions already
 * say what that means: taking a backup is {@link Permission#TRANSFER_EXPORT} and restoring one is
 * {@link Permission#TRANSFER_IMPORT}, which is what those permissions have meant since they
 * existed. Nothing new is invented for backups — sending the export somewhere else does not change
 * who may read a keyspace.
 *
 * <p>Listing what is in a destination is under the connection for the same reason. It is only ever
 * done in order to restore, and a listing that could be read by anybody would say which targets
 * exist and when each was last backed up.
 */
@jakarta.ws.rs.Path("/api/v1/connections/{connectionId}/backups")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Backups", description = "Taking a backup somewhere else, and getting one back")
@RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
public class Backups {

    private final BackupService service;

    @Inject
    Backups(BackupService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "What is already in a destination",
            description =
                    "Newest first, from the destination's own listing — no file is opened, so a"
                            + " bucket with a hundred backups answers as quickly as one with two.")
    @APIResponse(responseCode = "200", description = "The backups")
    @APIResponse(responseCode = "502", description = "The destination could not be read")
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<List<BackupSummary>> list(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("destinationId") Long destinationId,
            @QueryParam("prefix") String prefix) {
        return service.list(destinationId, prefix);
    }

    @GET
    @Path("/{name}")
    @Operation(
            summary = "What one backup says about itself",
            description =
                    "Its own request because reading the header means fetching the file, and a"
                            + " listing that did that for every row would be one nobody waits for."
                            + " Answers which target it was taken from, which is the question"
                            + " worth asking before restoring it into a different one.")
    @APIResponse(responseCode = "200", description = "The backup and its header")
    @APIResponse(responseCode = "502", description = "The backup could not be read")
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<BackupSummary> inspect(
            @PathParam("connectionId") Long connectionId,
            @PathParam("name") String name,
            @QueryParam("destinationId") Long destinationId) {
        return service.inspect(destinationId, name);
    }

    @POST
    @Operation(
            summary = "Take a backup now",
            description =
                    "Streams the keyspace into a compressed file and sends it. Nothing is held in"
                            + " memory: the keys go from the store, through gzip, onto a staging"
                            + " file, and from there to the destination.")
    @APIResponse(responseCode = "200", description = "What was written, and what fell off the end")
    @APIResponse(responseCode = "502", description = "The destination would not take it")
    @Audited("backup.take")
    @RequiresPermission(value = Permission.TRANSFER_EXPORT, connection = "connectionId")
    public Uni<BackupTaken> take(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("destinationId") Long destinationId,
            @QueryParam("prefix") String prefix,
            @QueryParam("match") String match,
            @QueryParam("keepLast") Integer keepLast) {
        return service.take(
                connectionId, destinationId, prefix, match == null ? "*" : match, keepLast);
    }

    @POST
    @Path("/restore")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Put a backup back",
            description =
                    "Through the same import the API already offers, with the same question about"
                            + " keys that are already there. Replacing is off unless asked for: a"
                            + " restore usually goes into a store that has moved on since, and"
                            + " quietly overwriting the newer data is the one outcome nobody"
                            + " wants by default.")
    @APIResponse(responseCode = "200", description = "What the restore did")
    @APIResponse(responseCode = "502", description = "The backup could not be fetched or read")
    @Audited("backup.restore")
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<ImportResult> restore(
            @PathParam("connectionId") Long connectionId, @Valid RestoreRequest request) {
        return service.restore(connectionId, request);
    }
}
