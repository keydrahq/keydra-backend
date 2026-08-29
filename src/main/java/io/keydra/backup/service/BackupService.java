package io.keydra.backup.service;

import io.keydra.backup.dto.BackupDtos.BackupSummary;
import io.keydra.backup.dto.BackupDtos.BackupTaken;
import io.keydra.backup.dto.BackupDtos.RestoreRequest;
import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.exception.BackupFailedException;
import io.keydra.backup.exception.DestinationConflictException;
import io.keydra.backup.persistence.BackupRepository;
import io.keydra.backup.store.CamelBackupStore;
import io.keydra.backup.store.StoredBackup;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.keys.dto.ExportKeysRequest;
import io.keydra.keys.dto.ImportResult;
import io.keydra.keys.service.KeyTransferService;
import io.keydra.telemetry.service.KeydraMeters;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * Taking a backup somewhere else, and getting one back.
 *
 * <p>Every step is its own boundary and nothing is held open across the next one: read the
 * destination, read the target, stream the keyspace into a local file, send the file, prune what
 * has fallen off the end. The shape is the one the scheduled runs already use, for the same reasons
 * — an upload takes as long as the file and the network make it, and a database session held across
 * it is a connection held across it.
 *
 * <p>The staging file is what makes "streamed rather than buffered" true. The keyspace never sits
 * in memory: it goes from the store, through a gzip stream, onto a local disk, and from there to
 * the destination — which also gives S3 the content length it will not take a body without.
 */
@ApplicationScoped
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private final BackupRepository repository;
    private final DestinationService destinations;
    private final ConnectionService connections;
    private final KeyTransferService transfers;
    private final BackupFile files;
    private final KeydraMeters meters;

    @Inject
    BackupService(
            BackupRepository repository,
            DestinationService destinations,
            ConnectionService connections,
            KeyTransferService transfers,
            BackupFile files,
            KeydraMeters meters) {
        this.repository = repository;
        this.destinations = destinations;
        this.connections = connections;
        this.transfers = transfers;
        this.files = files;
        this.meters = meters;
    }

    /** What is already in a destination, newest first. */
    public Uni<List<BackupSummary>> list(Long destinationId, String prefix) {
        return use(destinationId)
                .flatMap(destination -> destinations.store().list(destination, blankToNull(prefix)))
                .map(found -> found.stream().map(BackupSummary::of).toList());
    }

    /**
     * What one backup says about itself.
     *
     * <p>Fetched and opened, which is why it is a separate request from the listing: reading a
     * header means downloading the file, and a table that did that for every row would be a table
     * nobody waits for.
     */
    public Uni<BackupSummary> inspect(Long destinationId, String name) {
        return use(destinationId)
                .flatMap(
                        destination -> {
                            Path staged = temporary("inspect");
                            return destinations
                                    .store()
                                    .get(destination, name, staged)
                                    .map(
                                            ignored ->
                                                    new BackupSummary(
                                                            name,
                                                            size(staged),
                                                            null,
                                                            files.isEncrypted(staged),
                                                            // A backup encrypted to a key
                                                            // cannot be looked into without
                                                            // the other half, and this
                                                            // request has no business asking
                                                            // for one: it says what it knows
                                                            // and leaves the rest to the
                                                            // restore.
                                                            headerIfReadable(staged, destination)))
                                    .eventually(() -> discard(staged));
                        });
    }

    /**
     * Takes a backup of one target and sends it.
     *
     * @param prefix the start of the file name; each run stamps the rest, so nothing is overwritten
     * @param keepLast how many of this prefix's backups to leave behind, null or zero for all
     */
    public Uni<BackupTaken> take(
            Long connectionId, Long destinationId, String prefix, String match, Integer keepLast) {
        return use(destinationId)
                .flatMap(
                        destination ->
                                connections
                                        .load(connectionId)
                                        .flatMap(
                                                profile ->
                                                        write(profile, match, destination)
                                                                .flatMap(
                                                                        staged ->
                                                                                send(
                                                                                        destination,
                                                                                        profile,
                                                                                        staged,
                                                                                        prefix,
                                                                                        keepLast))))
                // Counted here rather than inside the writing, because what matters to
                // somebody watching is whether a backup happened, not which of the six
                // things it takes went wrong.
                .invoke(taken -> meters.backupTaken("done"))
                .onFailure()
                .invoke(failure -> meters.backupTaken("failed"));
    }

    /**
     * Puts a backup back into a target.
     *
     * <p>Through the same import the API already offers, with the same "replace or leave alone"
     * question — a restore is an import from a file, and having it be a second code path would mean
     * two answers to what happens to a key that is already there.
     */
    public Uni<ImportResult> restore(Long connectionId, RestoreRequest request) {
        return use(request.destinationId())
                .flatMap(
                        destination -> {
                            Path staged = temporary("restore");
                            return destinations
                                    .store()
                                    .get(destination, request.name(), staged)
                                    .flatMap(
                                            ignored ->
                                                    transfers.importStream(
                                                            connectionId,
                                                            files.keys(
                                                                    staged,
                                                                    secretFor(
                                                                            destination,
                                                                            request.privateKey())),
                                                            request.replace()))
                                    .onFailure()
                                    .transform(BackupService::plainest)
                                    .eventually(() -> discard(staged));
                        });
    }

    /**
     * What opens a backup from this destination.
     *
     * <p>A private key supplied with the request wins, because that is the mode where the server
     * has nothing: it borrows the key for this one restore and keeps none of it.
     */
    private static String secretFor(BackupDestination destination, String privateKey) {
        return privateKey != null && !privateKey.isBlank()
                ? privateKey
                : destination.encryptionPassphrase;
    }

    /** What the file says about itself, or nothing when this instance cannot open it. */
    private BackupHeader headerIfReadable(Path staged, BackupDestination destination) {
        try {
            return files.headerOf(staged, destination.encryptionPassphrase);
        } catch (BackupFailedException sealed) {
            LOG.debugf("Could not read the header of a backup from %s", destination.name);
            return null;
        }
    }

    /**
     * The complaint worth showing, out of however many arrived together.
     *
     * <p>A stream whose resource fails to open reports that failure alongside whatever the
     * finalizer then said, and Mutiny hands both over as one composite. Left alone it reaches the
     * caller as a 500 saying "multiple exceptions caught", which is true and useless — the sentence
     * somebody needs is the one about the key.
     */
    private static Throwable plainest(Throwable failure) {
        if (failure instanceof io.smallrye.mutiny.CompositeException composite) {
            return composite.getCauses().stream()
                    .filter(BackupFailedException.class::isInstance)
                    .findFirst()
                    .orElse(failure);
        }
        return failure;
    }

    /** The name a backup of this target would be given, so an interface can offer it. */
    public static String defaultPrefix(String connectionName) {
        String slug =
                connectionName
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "keydra" : slug;
    }

    // --- The steps ---------------------------------------------------------

    private Uni<BackupDestination> use(Long destinationId) {
        return repository
                .forUse(destinationId)
                .onItem()
                .ifNull()
                .failWith(() -> new DestinationConflictException("No such destination"))
                .invoke(
                        destination -> {
                            if (!destination.enabled) {
                                throw new DestinationConflictException(
                                        destination.name + " is turned off");
                            }
                        });
    }

    /** Streams the keyspace into a local gzipped file, and answers where it went and how big. */
    private Uni<Staged> write(
            ConnectionProfile profile, String match, BackupDestination destination) {
        Path file = temporary("backup");
        BackupHeader header = BackupHeader.of(profile.name, profile.id, match);
        return files.write(
                        file,
                        header,
                        transfers.export(profile.id, new ExportKeysRequest(null, match, null)),
                        destination.encryptionPassphrase,
                        destination.recipientKeys())
                .map(keys -> new Staged(file, keys, size(file), destination.encrypts()))
                .onFailure()
                .call(failure -> discard(file));
    }

    private Uni<BackupTaken> send(
            BackupDestination destination,
            ConnectionProfile profile,
            Staged staged,
            String prefix,
            Integer keepLast) {
        String base = blankToNull(prefix) == null ? defaultPrefix(profile.name) : prefix.trim();
        String name = BackupFile.nameFor(base, Instant.now(), staged.encrypted());
        CamelBackupStore store = destinations.store();

        return store.put(destination, name, staged.file())
                .flatMap(ignored -> prune(store, destination, base, keepLast))
                .map(
                        removed ->
                                new BackupTaken(
                                        name,
                                        staged.keys(),
                                        staged.size(),
                                        destination.name,
                                        removed))
                // put() moves the file for a local destination and copies it for the rest, so
                // this is the one that cleans up after the three that copy.
                .eventually(() -> discard(staged.file()));
    }

    /**
     * Deletes the backups of this prefix that have fallen off the end.
     *
     * <p>Only this prefix's. Two schedules pointed at the same bucket — a nightly keeping seven and
     * a weekly keeping four — must not delete each other's history, and the prefix is what tells
     * them apart.
     *
     * <p>A failure here does not fail the backup. The file is already safely there, and reporting
     * the whole thing as failed would send somebody looking for a missing backup that exists.
     */
    private Uni<List<String>> prune(
            CamelBackupStore store,
            BackupDestination destination,
            String prefix,
            Integer keepLast) {
        if (keepLast == null || keepLast <= 0) {
            return Uni.createFrom().item(List.of());
        }
        return store.list(destination, prefix + "-")
                .flatMap(
                        found -> {
                            List<StoredBackup> extra =
                                    found.size() <= keepLast
                                            ? List.of()
                                            : found.subList(keepLast, found.size());
                            Uni<List<String>> chain = Uni.createFrom().item(List.of());
                            for (StoredBackup old : extra) {
                                chain =
                                        chain.flatMap(
                                                names ->
                                                        store.delete(destination, old.name())
                                                                .map(
                                                                        ignored ->
                                                                                append(
                                                                                        names,
                                                                                        old
                                                                                                .name())));
                            }
                            return chain;
                        })
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.warnf(
                                    failure, "Could not prune old backups in %s", destination.name);
                            return List.of();
                        });
    }

    // --- Local files -------------------------------------------------------

    /** One staged backup: where it is, how many keys went in, and how big it came out. */
    private record Staged(Path file, long keys, long size, boolean encrypted) {}

    private static Path temporary(String what) {
        try {
            Path file = Files.createTempFile("keydra-" + what, BackupFile.SUFFIX);
            // createTempFile makes an empty file; the writers want to create their own.
            Files.deleteIfExists(file);
            return file;
        } catch (IOException unwritable) {
            throw new BackupFailedException("Could not make a staging file", unwritable);
        }
    }

    private static long size(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /**
     * Removes a staging file, and never turns a failure into the answer.
     *
     * <p>Not moved to a worker: deleting one file is a system call, and moving it would put
     * everything after it on a worker thread — where Hibernate Reactive refuses to run.
     */
    private static Uni<Void> discard(Path file) {
        return Uni.createFrom()
                .item(
                        () -> {
                            try {
                                Files.deleteIfExists(file);
                            } catch (IOException ignored) {
                                LOG.debugf("Could not remove the staging file %s", file);
                            }
                            return (Void) null;
                        });
    }

    private static List<String> append(List<String> names, String name) {
        return java.util.stream.Stream.concat(names.stream(), java.util.stream.Stream.of(name))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
