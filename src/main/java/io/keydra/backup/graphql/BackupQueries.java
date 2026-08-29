package io.keydra.backup.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.backup.dto.BackupDtos.BackupSummary;
import io.keydra.backup.dto.BackupDtos.BackupTaken;
import io.keydra.backup.dto.BackupDtos.DestinationCheck;
import io.keydra.backup.dto.BackupDtos.DestinationRequest;
import io.keydra.backup.dto.BackupDtos.DestinationSummary;
import io.keydra.backup.dto.BackupDtos.KeyPair;
import io.keydra.backup.dto.BackupDtos.RestoreRequest;
import io.keydra.backup.service.BackupKeys;
import io.keydra.backup.service.BackupService;
import io.keydra.backup.service.DestinationService;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.keys.dto.ImportResult;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Where backups go, and what is already there.
 *
 * <p>Two things with different guards, which is why they are annotated one operation at a time
 * rather than once on the class. A destination is a bucket with credentials in it and belongs to
 * whoever administers the instance; a backup is data belonging to one target, and reading the list
 * is the same right as restoring from it. Putting one annotation on the class would have to pick
 * the looser of the two.
 *
 * <p>Transport only, calling the same services the resources call.
 */
@GraphQLApi
@OneAtATime
public class BackupQueries {

    private final DestinationService destinations;
    private final BackupService backups;

    @Inject
    BackupQueries(DestinationService destinations, BackupService backups) {
        this.destinations = destinations;
        this.backups = backups;
    }

    @Query("backupDestinations")
    @Description("Every destination, without the credentials they hold")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.BACKUP_MANAGE)
    public Uni<List<DestinationSummary>> backupDestinations() {
        return destinations.list();
    }

    /**
     * What is already in a destination.
     *
     * <p>A plain list rather than a connection, because the paging is not ours to do: this comes
     * from the destination's own listing — an S3 bucket, a directory — and slicing it here would
     * mean fetching all of it to return twenty. If it ever needs paging it will be the store's
     * continuation token, not an offset of ours.
     */
    @Query("backups")
    @Description("What is already in a destination, newest first")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<List<BackupSummary>> backups(
            @Name("connectionId") @Description("The target these would be restored into")
                    Long connectionId,
            @Name("destinationId") @Description("Which destination to read") Long destinationId,
            @Name("prefix") @Description("Only names beginning with this") String prefix) {
        return backups.list(destinationId, prefix);
    }

    @Query("backup")
    @Description("One backup's header, read without downloading the whole file")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<BackupSummary> backup(
            @Name("connectionId") @Description("The target this would be restored into")
                    Long connectionId,
            @Name("destinationId") @Description("Which destination to read") Long destinationId,
            @Name("name") @Description("The file's name in that destination") String name) {
        return backups.inspect(destinationId, name);
    }

    // --- Changing them ------------------------------------------------------

    @Mutation("createBackupDestination")
    @Description("Adds a destination for backups to be written to")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.BACKUP_MANAGE)
    public Uni<DestinationSummary> createBackupDestination(
            @Name("destination") @Valid DestinationRequest destination) {
        return destinations.create(destination);
    }

    @Mutation("updateBackupDestination")
    @Description("Changes a destination")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.BACKUP_MANAGE)
    public Uni<DestinationSummary> updateBackupDestination(
            @Name("id") Long id, @Name("destination") @Valid DestinationRequest destination) {
        return destinations.update(id, destination);
    }

    /** Answers whether there was one to remove; deleting something twice is not an error. */
    @Mutation("deleteBackupDestination")
    @Description("Removes a destination")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.BACKUP_MANAGE)
    public Uni<Boolean> deleteBackupDestination(@Name("id") Long id) {
        return destinations.delete(id);
    }

    /**
     * Reaches the destination and reports what happened.
     *
     * <p>A mutation rather than a query: it leaves the building. A query is expected to be safe to
     * run twice and safe to cache, and writing a probe file into somebody's bucket is neither.
     *
     * <p>Takes the whole destination so one can be tried before it is saved, with an id alongside
     * for taking unchanged secrets from a saved one — the difference between finding out now and
     * finding out when a backup fails at three in the morning.
     */
    @Mutation("checkBackupDestination")
    @Description("Reaches the destination and reports what happened")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.BACKUP_MANAGE)
    public Uni<DestinationCheck> checkBackupDestination(
            @Name("id") @Description("A saved destination to take unchanged secrets from") Long id,
            @Name("destination") @Valid DestinationRequest destination) {
        return destinations.check(id, destination);
    }

    /**
     * Makes a key pair for encrypting backups.
     *
     * <p>A mutation because it produces something new every time it is called, which is the one
     * thing a query must never do. The private half is returned once and never stored — this is the
     * only moment it exists anywhere Keydra can see.
     */
    @Mutation("generateBackupKeyPair")
    @Description("Makes a key pair; the private half is shown once and never stored")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.BACKUP_MANAGE)
    public Uni<KeyPair> generateBackupKeyPair() {
        BackupKeys.Pair pair = BackupKeys.generate();
        return Uni.createFrom().item(new KeyPair(pair.publicKey(), pair.privateKey()));
    }

    @Mutation("takeBackup")
    @Description("Writes a backup of a target into a destination")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.TRANSFER_EXPORT, connection = "connectionId")
    public Uni<BackupTaken> takeBackup(
            @Name("connectionId") @Description("The target to read") Long connectionId,
            @Name("destinationId") @Description("Where to write it") Long destinationId,
            @Name("prefix") @Description("A folder within the destination") String prefix,
            @Name("match") @Description("A glob, or every key") String match,
            @Name("keepLast") @Description("How many previous backups to keep") Integer keepLast) {
        return backups.take(
                connectionId, destinationId, prefix, match == null ? "*" : match, keepLast);
    }

    @Mutation("restoreBackup")
    @Description("Writes a backup's keys into a target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.TRANSFER_IMPORT, connection = "connectionId")
    public Uni<ImportResult> restoreBackup(
            @Name("connectionId") @Description("The target to write into") Long connectionId,
            @Name("restore") @Valid RestoreRequest restore) {
        return backups.restore(connectionId, restore);
    }
}
