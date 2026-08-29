package io.keydra.keys.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.engine.KeyQuery;
import io.keydra.keys.dto.CopyKeyRequest;
import io.keydra.keys.dto.DeleteKeysRequest;
import io.keydra.keys.dto.ExpireKeyRequest;
import io.keydra.keys.dto.ExportKeysRequest;
import io.keydra.keys.dto.ExportedKey;
import io.keydra.keys.dto.ImportKeysRequest;
import io.keydra.keys.dto.ImportResult;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.keys.dto.KeyOperationResult;
import io.keydra.keys.dto.MigrateKeysRequest;
import io.keydra.keys.dto.MigrationJob;
import io.keydra.keys.dto.NamespaceNode;
import io.keydra.keys.dto.PurgeKeysRequest;
import io.keydra.keys.dto.RenameKeyRequest;
import io.keydra.keys.service.KeyMigrationService;
import io.keydra.keys.service.KeyService;
import io.keydra.keys.service.KeyTransferService;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * Key browsing and mutation for one connection.
 *
 * <p>Key names travel in query strings and request bodies, never in the path: a key may contain any
 * byte, including slashes and hashes, and path encoding for those is a reliable source of bugs.
 * Bulk and single deletes therefore share one endpoint.
 */
@Path("/api/v1/connections/{connectionId}/keys")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Keys", description = "Browse and mutate keys on a target")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Keys {

    private final KeyService service;
    private final KeyTransferService transfers;
    private final KeyMigrationService migrations;
    private final SecurityIdentity identity;

    @Inject
    Keys(
            KeyService service,
            KeyTransferService transfers,
            KeyMigrationService migrations,
            SecurityIdentity identity) {
        this.service = service;
        this.transfers = transfers;
        this.migrations = migrations;
        this.identity = identity;
    }

    @GET
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @Operation(
            summary = "Stream keys matching a filter",
            description =
                    "Walks the keyspace with SCAN and streams each key with its type and TTL as a"
                            + " server-sent event, so results appear while the scan is still"
                            + " running. KEYS is never used.")
    @APIResponse(responseCode = "200", description = "Stream of keys")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Multi<KeyEntry> scan(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @QueryParam("match") String match,
            @QueryParam("count") Integer count,
            @QueryParam("type") String type) {
        return service.scan(
                connectionId,
                database,
                new KeyQuery(match, count == null ? KeyQuery.DEFAULT_COUNT : count, type));
    }

    @GET
    @Path("/tree")
    @Operation(
            summary = "List the namespace level below a prefix",
            description =
                    "Groups keys on the delimiter to build one level of the namespace tree. Counts"
                            + " are over a bounded sample of the keyspace, not a full census.")
    @APIResponse(responseCode = "200", description = "Namespace nodes")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<List<NamespaceNode>> tree(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @QueryParam("prefix") String prefix,
            @QueryParam("delimiter") String delimiter,
            @QueryParam("count") Integer count) {
        return service.tree(
                connectionId,
                database,
                prefix,
                delimiter,
                count == null ? KeyQuery.DEFAULT_COUNT : count);
    }

    @POST
    @Path("/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Delete one or more keys")
    @APIResponse(responseCode = "200", description = "How many keys were removed")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.delete")
    @RequiresPermission(value = Permission.KEYS_DELETE, connection = "connectionId")
    public Uni<KeyOperationResult> delete(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @Valid DeleteKeysRequest request) {
        return service.delete(connectionId, database, request.keys(), request.confirmTarget());
    }

    @POST
    @Path("/purge")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Delete everything a pattern matches",
            description =
                    "Walks the keyspace with the cursor and deletes a batch at a time, so clearing"
                        + " a namespace does not mean naming every key in it. Answers how many keys"
                        + " the store actually removed, which can be fewer than were found: a key"
                        + " may expire between being walked past and being deleted.")
    @APIResponse(responseCode = "200", description = "How many keys were removed")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.purge")
    @RequiresPermission(value = Permission.KEYS_DELETE, connection = "connectionId")
    public Uni<KeyOperationResult> purge(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @Valid PurgeKeysRequest request) {
        return service.purge(connectionId, database, request);
    }

    @POST
    @Path("/rename")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Rename a key",
            description =
                    "Without the replace flag the rename fails when the target exists, so"
                            + " overwriting unrelated data takes a deliberate choice.")
    @APIResponse(responseCode = "200", description = "1 when the key was renamed, 0 otherwise")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.rename")
    @RequiresPermission(value = Permission.KEYS_WRITE, connection = "connectionId")
    public Uni<KeyOperationResult> rename(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @Valid RenameKeyRequest request) {
        return service.rename(connectionId, database, request);
    }

    @POST
    @Path("/copy")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Copy a key to a new name",
            description =
                    "The store does the copying, so a large value never crosses the wire. Without"
                            + " the replace flag the copy fails when the target exists.")
    @APIResponse(responseCode = "200", description = "1 when the key was copied, 0 otherwise")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.copy")
    @RequiresPermission(value = Permission.KEYS_WRITE, connection = "connectionId")
    public Uni<KeyOperationResult> copy(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @Valid CopyKeyRequest request) {
        return service.copy(connectionId, database, request);
    }

    @POST
    @Path("/export")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Export keys as the store's own serialisation",
            description =
                    "Answers a JSON array, streamed as the keys are read, of every requested key"
                        + " with its remaining TTL and its value as the store serialises it. The"
                        + " same document is what the import endpoint takes back. Give either an"
                        + " explicit list of keys or a glob to walk the keyspace for.")
    @APIResponse(responseCode = "200", description = "The exported keys")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @APIResponse(responseCode = "501", description = "This store cannot serialise a key")
    @RequiresPermission(value = Permission.TRANSFER_EXPORT, connection = "connectionId")
    public Multi<ExportedKey> export(
            @PathParam("connectionId") Long connectionId, @Valid ExportKeysRequest request) {
        return transfers.export(connectionId, request);
    }

    @POST
    @Path("/import")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Restore exported keys",
            description =
                    "Writes back a document produced by the export endpoint. Existing keys are"
                            + " left alone unless replacing is asked for, and one key the store"
                            + " refuses does not stop the rest — the answer says how many of each"
                            + " there were.")
    @APIResponse(responseCode = "200", description = "What the import did")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @APIResponse(responseCode = "501", description = "This store cannot restore a key")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.import")
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<ImportResult> importKeys(
            @PathParam("connectionId") Long connectionId, @Valid ImportKeysRequest request) {
        return transfers.importKeys(connectionId, request);
    }

    @POST
    @Path("/migrate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Move keys to another target",
            description =
                    "Starts a migration and answers the job it created, before any keys have moved."
                        + " Keydra reads from this target and writes to the other one, a batch at a"
                        + " time, so the keyspace is never held in memory and the job works even"
                        + " when the two servers cannot reach each other. Progress arrives on the"
                        + " notification hub under MigrationProgress.")
    @APIResponse(responseCode = "200", description = "The job that was started")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @APIResponse(responseCode = "409", description = "The target is the source")
    @APIResponse(responseCode = "501", description = "One of the stores cannot serialise a key")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.migrate")
    @RequiresPermission(value = Permission.MIGRATION_RUN, connection = "connectionId")
    public Uni<MigrationJob> migrate(
            @PathParam("connectionId") Long connectionId, @Valid MigrateKeysRequest request) {
        return migrations.start(connectionId, request, whoIsAsking());
    }

    /** The name to record against a migration, or nothing on an instance with security off. */
    private String whoIsAsking() {
        return identity == null || identity.isAnonymous()
                ? null
                : identity.getPrincipal().getName();
    }

    @GET
    @Path("/migrate")
    @Operation(
            summary = "Migrations started from this target",
            description =
                    "Running and finished alike, so a page that was reloaded can pick a job back"
                            + " up rather than losing sight of it.")
    @APIResponse(responseCode = "200", description = "The jobs")
    @RequiresPermission(value = Permission.MIGRATION_RUN, connection = "connectionId")
    public Uni<List<MigrationJob>> migrations(@PathParam("connectionId") Long connectionId) {
        return migrations.jobsFor(connectionId);
    }

    @DELETE
    @Path("/migrate/{jobId}")
    @Operation(
            summary = "Stop a migration",
            description =
                    "The keys already written stay written: a migration is a stream of"
                            + " independent writes, not a transaction.")
    @APIResponse(responseCode = "204", description = "The job was stopped")
    @APIResponse(responseCode = "404", description = "No job with that id is running")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.migrate.cancel")
    @RequiresPermission(value = Permission.MIGRATION_RUN, connection = "connectionId")
    public Uni<Response> cancelMigration(
            @PathParam("connectionId") Long connectionId, @PathParam("jobId") String jobId) {
        return Uni.createFrom()
                .item(
                        migrations.cancel(jobId)
                                ? Response.noContent().build()
                                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Path("/expire")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Set or clear a key's TTL",
            description = "Omitting ttlSeconds removes the expiry.")
    @APIResponse(responseCode = "200", description = "1 when the key's expiry changed")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("key.expire")
    @RequiresPermission(value = Permission.KEYS_WRITE, connection = "connectionId")
    public Uni<KeyOperationResult> expire(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @Valid ExpireKeyRequest request) {
        return service.expire(connectionId, database, request);
    }
}
