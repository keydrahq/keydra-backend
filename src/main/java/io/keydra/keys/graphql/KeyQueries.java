package io.keydra.keys.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.keys.dto.CopyKeyRequest;
import io.keydra.keys.dto.ExpireKeyRequest;
import io.keydra.keys.dto.ImportKeysRequest;
import io.keydra.keys.dto.ImportResult;
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
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.List;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Keys: what is there, and what can be done to them.
 *
 * <p>Enumeration is not here — it is a subscription, because a keyspace is walked rather than
 * fetched and the first names are worth drawing while the walk continues. What is here is
 * everything that answers at once: the namespace tree a browser draws its folders from, and the
 * operations that change keys.
 *
 * <p>Reading and writing are guarded apart, and deleting apart from both. Somebody who may look at
 * a keyspace is not thereby somebody who may empty it.
 *
 * <p>Transport only, calling the same services the resources call.
 */
@GraphQLApi
@OneAtATime
public class KeyQueries {

    private final KeyService keys;
    private final KeyTransferService transfers;
    private final KeyMigrationService migrations;

    @Inject
    KeyQueries(KeyService keys, KeyTransferService transfers, KeyMigrationService migrations) {
        this.keys = keys;
        this.transfers = transfers;
        this.migrations = migrations;
    }

    /**
     * The keyspace as folders, one level at a time.
     *
     * <p>A level rather than the whole tree: a keyspace with a million keys has a tree nobody can
     * hold, and a browser only ever draws the branch somebody opened.
     */
    @Query("namespaceTree")
    @Description("One level of the keyspace, grouped by a delimiter")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<List<NamespaceNode>> namespaceTree(
            @Name("connectionId") Long connectionId,
            @Name("database") @Description("Which database, or the profile's own") Integer database,
            @Name("prefix") @Description("The branch to open, or the root") String prefix,
            @Name("delimiter") @DefaultValue(":") @Description("What separates a folder from a key")
                    String delimiter,
            @Name("count") @DefaultValue("5000") @Description("How many keys to walk")
                    Integer count) {
        return keys.tree(connectionId, database, prefix, delimiter, count == null ? 5000 : count);
    }

    // --- Changing them ------------------------------------------------------

    @Mutation("deleteKeys")
    @Description("Removes the named keys")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_DELETE, connection = "connectionId")
    @Audited("key.delete")
    public Uni<KeyOperationResult> deleteKeys(
            @Name("connectionId") Long connectionId,
            @Name("database") Integer database,
            @Name("keys") List<String> keys,
            @Name("confirmTarget")
                    @Description("The target's own name, where the target is guarded")
                    String confirmTarget) {
        return this.keys.delete(connectionId, database, keys, confirmTarget);
    }

    @Mutation("purgeKeys")
    @Description("Removes every key matching a glob, a batch at a time")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_DELETE, connection = "connectionId")
    @Audited("key.purge")
    public Uni<KeyOperationResult> purgeKeys(
            @Name("connectionId") Long connectionId,
            @Name("database") Integer database,
            @Name("purge") @Valid PurgeKeysRequest purge) {
        return keys.purge(connectionId, database, purge);
    }

    @Mutation("renameKey")
    @Description("Renames a key")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_WRITE, connection = "connectionId")
    public Uni<KeyOperationResult> renameKey(
            @Name("connectionId") Long connectionId,
            @Name("database") Integer database,
            @Name("rename") @Valid RenameKeyRequest rename) {
        return keys.rename(connectionId, database, rename);
    }

    @Mutation("copyKey")
    @Description("Copies a key, here or to another target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_WRITE, connection = "connectionId")
    @Audited("key.copy")
    public Uni<KeyOperationResult> copyKey(
            @Name("connectionId") Long connectionId,
            @Name("database") Integer database,
            @Name("copy") @Valid CopyKeyRequest copy) {
        return keys.copy(connectionId, database, copy);
    }

    @Mutation("expireKey")
    @Description("Sets or clears a key's time to live")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_WRITE, connection = "connectionId")
    public Uni<KeyOperationResult> expireKey(
            @Name("connectionId") Long connectionId,
            @Name("database") Integer database,
            @Name("expire") @Valid ExpireKeyRequest expire) {
        return keys.expire(connectionId, database, expire);
    }

    @Mutation("importKeys")
    @Description("Writes keys into a target from what was exported")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    @Audited("key.import")
    public Uni<ImportResult> importKeys(
            @Name("connectionId") Long connectionId, @Name("keys") @Valid ImportKeysRequest keys) {
        return transfers.importKeys(connectionId, keys);
    }

    @Mutation("startMigration")
    @Description("Starts moving keys from one target to another; answers before any have moved")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MIGRATION_RUN, connection = "connectionId")
    @Audited("key.migrate")
    public Uni<MigrationJob> startMigration(
            @Name("connectionId") @Description("The target to read from") Long connectionId,
            @Name("migration") @Valid MigrateKeysRequest migration) {
        return migrations.start(connectionId, migration, null);
    }

    @Mutation("cancelMigration")
    @Description("Stops a migration; what has moved stays moved")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MIGRATION_RUN, connection = "connectionId")
    public Uni<Boolean> cancelMigration(
            @Name("connectionId") Long connectionId, @Name("jobId") String jobId) {
        return Uni.createFrom().item(migrations.cancel(jobId));
    }
}
