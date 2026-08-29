package io.keydra.backup.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.backup.dto.BackupDtos.DestinationCheck;
import io.keydra.backup.dto.BackupDtos.DestinationRequest;
import io.keydra.backup.dto.BackupDtos.DestinationSummary;
import io.keydra.backup.dto.BackupDtos.KeyPair;
import io.keydra.backup.service.BackupKeys;
import io.keydra.backup.service.DestinationService;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The places backups are sent, as rows.
 *
 * <p>Configuring one is an administrator's job and deliberately not an operator's: a destination
 * carries credentials to somewhere outside Keydra, and somebody who may take a backup of one server
 * is not thereby somebody who may decide backups leave for a bucket of their choosing. Taking and
 * restoring stay permissions about the target.
 *
 * <p>A secret may be sent here and is never sent back. Like a target's password, the only thing the
 * API will say about one is whether it exists.
 */
@Path("/api/v1/backup-destinations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Backup destinations", description = "Where backups are sent")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.BACKUP_MANAGE)
public class Destinations {

    private final DestinationService service;

    @Inject
    Destinations(DestinationService service) {
        this.service = service;
    }

    @GET
    @Operation(summary = "Every configured destination")
    @APIResponse(responseCode = "200", description = "The destinations")
    public Uni<List<DestinationSummary>> list() {
        return service.list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a destination")
    @APIResponse(responseCode = "201", description = "Added")
    @APIResponse(responseCode = "409", description = "That name is taken, or a field is missing")
    @Audited("backup.destination.create")
    public Uni<RestResponse<DestinationSummary>> create(@Valid DestinationRequest request) {
        return service.create(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change a destination",
            description = "An absent secret leaves the stored one alone; an empty one clears it.")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "No such destination, or a field is missing")
    @Audited("backup.destination.update")
    public Uni<DestinationSummary> update(
            @PathParam("id") Long id, @Valid DestinationRequest request) {
        return service.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a destination",
            description =
                    "The backups already there stay where they are. Deleting the record of how to"
                            + " reach a place is not deleting what is in it.")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("backup.destination.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/keys")
    @Operation(
            summary = "Generate a key pair for encrypting backups",
            description =
                    "The private half is in this response and nowhere else — not in the database,"
                            + " not in a log, not in any later request. Keep it somewhere that is"
                            + " not this server, which is the entire point of it.")
    @APIResponse(responseCode = "200", description = "A key pair, handed over once")
    @Audited("backup.key.generate")
    public Uni<KeyPair> generateKeyPair() {
        BackupKeys.Pair pair = BackupKeys.generate();
        return Uni.createFrom().item(new KeyPair(pair.publicKey(), pair.privateKey()));
    }

    @POST
    @Path("/check")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Try a destination that has not been saved",
            description =
                    "The same round trip as the check on a saved destination — write, look,"
                            + " remove — against what the form currently says, and storing"
                            + " nothing. An edit sends no secret it did not change, so pass the id"
                            + " being edited and the stored ones are used.")
    @APIResponse(responseCode = "200", description = "What the attempt found, working or not")
    @Audited("backup.destination.check")
    public Uni<DestinationCheck> checkDraft(
            @QueryParam("id") Long id, @Valid DestinationRequest request) {
        return service.check(id, request);
    }

    @POST
    @Path("/{id}/check")
    @Operation(
            summary = "Find out whether it works",
            description =
                    "Writes a small file, looks for it and removes it again — the whole round trip"
                            + " rather than a connection test, because credentials that can log in"
                            + " and not write are the commonest way a destination is wrong.")
    @APIResponse(responseCode = "200", description = "What the attempt found, working or not")
    @Audited("backup.destination.check")
    public Uni<DestinationCheck> check(@PathParam("id") Long id) {
        return service.check(id);
    }
}
