package io.keydra.backup.service;

import io.keydra.backup.dto.BackupDtos.DestinationCheck;
import io.keydra.backup.dto.BackupDtos.DestinationRequest;
import io.keydra.backup.dto.BackupDtos.DestinationSummary;
import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.entity.BackupRecipient;
import io.keydra.backup.exception.DestinationConflictException;
import io.keydra.backup.mapper.BackupDestinationMapper;
import io.keydra.backup.persistence.BackupRepository;
import io.keydra.backup.store.CamelBackupStore;
import io.keydra.backup.store.DestinationUri;
import io.keydra.backup.store.StoredBackup;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The places backups are sent, as rows somebody edits.
 *
 * <p>The same argument that made connection profiles rows rather than a list in a configuration
 * file: adding somewhere to back up to should not be a redeploy, and the credentials should live
 * once rather than once per schedule.
 */
@ApplicationScoped
public class DestinationService {

    private final BackupRepository repository;
    private final BackupDestinationMapper mapper;
    private final CamelBackupStore store;

    @Inject
    DestinationService(
            BackupRepository repository, BackupDestinationMapper mapper, CamelBackupStore store) {
        this.repository = repository;
        this.mapper = mapper;
        this.store = store;
    }

    /**
     * The one store there is.
     *
     * <p>There was a lookup here while there was a class per protocol. There is one class because
     * every destination is a Camel endpoint URI and the kind only decides how that string is built
     * — which is the whole reason the framework is in the build.
     */
    public CamelBackupStore store() {
        return store;
    }

    @WithSession
    public Uni<List<DestinationSummary>> list() {
        return repository.all().map(found -> found.stream().map(mapper::toSummary).toList());
    }

    @WithTransaction
    public Uni<DestinationSummary> create(DestinationRequest request) {
        return repository
                .byName(request.name())
                .flatMap(
                        existing -> {
                            if (existing != null) {
                                return Uni.createFrom()
                                        .failure(
                                                new DestinationConflictException(
                                                        "A destination called "
                                                                + request.name()
                                                                + " already exists"));
                            }
                            BackupDestination destination = new BackupDestination();
                            mapper.apply(request, destination);
                            destination.recipients = asked(request, List.of());
                            requireWhatTheKindNeeds(destination);
                            return repository
                                    .save(destination)
                                    .call(
                                            saved ->
                                                    repository.replaceRecipients(
                                                            saved.id, saved.recipients))
                                    .map(mapper::toSummary);
                        });
    }

    @WithTransaction
    public Uni<DestinationSummary> update(Long id, DestinationRequest request) {
        return repository
                .byId(id)
                .flatMap(
                        destination -> {
                            if (destination == null) {
                                return Uni.createFrom()
                                        .failure(
                                                new DestinationConflictException(
                                                        "No such destination"));
                            }
                            mapper.apply(request, destination);
                            destination.recipients = asked(request, destination.recipients);
                            requireWhatTheKindNeeds(destination);
                            // Camel caches endpoints by URI and a URI carries the credentials,
                            // so an edited password would otherwise stay unused behind the old
                            // one until a restart.
                            store.forget(id);
                            return repository
                                    .replaceRecipients(id, destination.recipients)
                                    .map(ignored -> mapper.toSummary(destination));
                        });
    }

    @WithTransaction
    public Uni<Boolean> delete(Long id) {
        store.forget(id);
        return repository.delete(id);
    }

    /**
     * Whether a destination actually works, found out now.
     *
     * <p>Writes a small file, looks for it, and takes it away again — the whole round trip rather
     * than a connection test, because credentials that can log in and not write are the commonest
     * way a backup destination is wrong. The moment to discover that is while somebody is looking
     * at the form, not at three in the morning three weeks later.
     *
     * <p>Never fails the request. "It did not work, and here is what it said" is the answer being
     * asked for, so it arrives as a result rather than as an error somebody has to interpret.
     */
    public Uni<DestinationCheck> check(Long id) {
        return repository
                .forUse(id)
                .onItem()
                .ifNull()
                .failWith(() -> new DestinationConflictException("No such destination"))
                .flatMap(this::probe);
    }

    /**
     * Tries a destination as a form currently describes it, saved or not.
     *
     * <p>The whole round trip — write a small file, look for it, remove it again — because
     * credentials that can log in and not write are the commonest way a destination is wrong, and
     * the moment to find that out is while somebody is looking at the form.
     *
     * @param id the destination being edited, or null for a new one; an edit sends no secret it did
     *     not change, so the stored ones are what the attempt uses
     */
    public Uni<DestinationCheck> check(Long id, DestinationRequest request) {
        Uni<BackupDestination> stored =
                id == null ? Uni.createFrom().nullItem() : repository.forUse(id);
        return stored.map(existing -> draft(existing, request)).flatMap(this::probe);
    }

    /**
     * A destination as the form describes it, detached from any session.
     *
     * <p>Copied rather than edited in place, so a session cannot flush a form somebody is still
     * filling in.
     */
    private BackupDestination draft(BackupDestination stored, DestinationRequest request) {
        BackupDestination draft = new BackupDestination();
        if (stored != null) {
            draft.name = stored.name;
            draft.kind = stored.kind;
            draft.enabled = stored.enabled;
            draft.location = stored.location;
            draft.path = stored.path;
            draft.port = stored.port;
            draft.endpoint = stored.endpoint;
            draft.region = stored.region;
            draft.pathStyle = stored.pathStyle;
            draft.accessKey = stored.accessKey;
            draft.secretKey = stored.secretKey;
            draft.privateKey = stored.privateKey;
            draft.passphrase = stored.passphrase;
            draft.encryptionPassphrase = stored.encryptionPassphrase;
            draft.recipients = stored.recipients;
            draft.tls = stored.tls;
            draft.tunnelId = stored.tunnelId;
        }
        mapper.apply(request, draft);
        draft.recipients = asked(request, draft.recipients);
        requireOneWayIn(draft);
        return draft;
    }

    private Uni<DestinationCheck> probe(BackupDestination destination) {
        String name = ".keydra-check-" + Instant.now().toEpochMilli();
        Path staged = staged();
        // A custom endpoint can be written to and not read back, so its round trip stops at
        // the write — which is still the thing worth finding out.
        boolean readable = DestinationUri.readable(destination);

        return store.put(destination, name, staged)
                .flatMap(
                        ignored ->
                                readable
                                        ? store.list(destination, ".keydra-check-")
                                        : Uni.createFrom().item(List.<StoredBackup>of()))
                .flatMap(
                        found ->
                                (readable
                                                ? store.delete(destination, name)
                                                : Uni.createFrom().voidItem())
                                        .map(ignored -> report(readable, found)))
                .onFailure()
                .recoverWithItem(failure -> new DestinationCheck(false, failure.getMessage()))
                .eventually(() -> deleteQuietly(staged));
    }

    private static DestinationCheck report(boolean readable, List<StoredBackup> found) {
        if (!readable) {
            return new DestinationCheck(
                    true,
                    "Wrote a test file. This kind of destination cannot be read back, so it"
                            + " stays.");
        }
        return new DestinationCheck(
                true,
                found.isEmpty()
                        ? "Wrote and removed a test file, though the destination did not list it"
                        : "Wrote and removed a test file");
    }

    /**
     * A tiny local file to send. Its content says what it is, for whoever finds one left behind.
     */
    private static Path staged() {
        try {
            Path file = Files.createTempFile("keydra-check", ".txt");
            Files.writeString(
                    file,
                    "Written by Keydra to check this destination. Safe to delete.\n",
                    StandardCharsets.UTF_8);
            return file;
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    private static Uni<Void> deleteQuietly(Path file) {
        return Uni.createFrom()
                .item(
                        () -> {
                            try {
                                Files.deleteIfExists(file);
                            } catch (IOException ignored) {
                                // A temporary file that outlives its use is litter, not a
                                // failure worth replacing the answer with.
                            }
                            return (Void) null;
                        });
    }

    /**
     * The field each kind cannot work without, refused while somebody is looking at the form.
     *
     * <p>Not validation annotations, because what is required depends on the kind: a bucket is
     * mandatory for S3 and meaningless for a local directory, and a record cannot say that.
     */
    private static void requireWhatTheKindNeeds(BackupDestination destination) {
        switch (destination.kind) {
            case S3 -> require(destination.location, "An S3 destination needs a bucket");
            case SFTP -> {
                require(destination.location, "An SFTP destination needs a host");
                require(destination.accessKey, "An SFTP destination needs a username");
            }
            case FTP -> require(destination.location, "An FTP destination needs a host");
            case AZURE_BLOB -> {
                require(destination.accessKey, "Azure Blob needs a storage account");
                require(destination.location, "Azure Blob needs a container");
            }
            case GCS -> require(destination.location, "Google Cloud Storage needs a bucket");
            case CUSTOM -> {
                require(destination.location, "This destination needs an endpoint address");
                if (!destination.location.matches("^[a-zA-Z0-9+.-]+:.*")) {
                    throw new DestinationConflictException(
                            "An endpoint address needs a scheme and a colon, like"
                                    + " jms:queue:backups");
                }
            }
            case LOCAL -> {
                // Nothing: the path is optional and the root is the deployment's to choose.
            }
        }
        requireOneWayIn(destination);
    }

    /**
     * A place has one way in.
     *
     * <p>Both a passphrase and a recipient key would be a question about which one any given file
     * used — answerable by opening it, and not by anybody looking at the destination. Refused where
     * somebody can fix it rather than discovered during a restore.
     */
    private static void requireOneWayIn(BackupDestination destination) {
        boolean hasPassphrase =
                destination.encryptionPassphrase != null
                        && !destination.encryptionPassphrase.isBlank();
        if (hasPassphrase && destination.encryptsToKey()) {
            throw new DestinationConflictException(
                    "A destination encrypts with a passphrase or to a key, not both. Clear one.");
        }
        for (BackupRecipient recipient : destination.recipients) {
            if (!BackupKeys.isPublicKey(recipient.publicKey)) {
                throw new DestinationConflictException(
                        "That is not a Keydra public key; one starts with "
                                + BackupKeys.PUBLIC_PREFIX);
            }
        }
        Set<String> labels = new HashSet<>();
        for (BackupRecipient recipient : destination.recipients) {
            // Two keys called the same thing is a list where removing the wrong one is a
            // coin toss, and the label is the only thing anybody reads.
            if (!labels.add(recipient.label.trim().toLowerCase(java.util.Locale.ROOT))) {
                throw new DestinationConflictException(
                        "Two keys here are called "
                                + recipient.label.trim()
                                + ". Names have to"
                                + " differ, because the name is how one is picked out.");
            }
        }
    }

    /**
     * The recipients a request asks for, or the ones already stored when it does not say.
     *
     * <p>The same absent-means-keep rule the secrets follow, and for a related reason: a form that
     * is about something else should not silently turn a destination's encryption off. Empty is
     * different from absent and does exactly that, deliberately — it is how somebody stops
     * encrypting.
     */
    private static List<BackupRecipient> asked(
            DestinationRequest request, List<BackupRecipient> stored) {
        if (request.recipients() == null) {
            return stored == null ? List.of() : stored;
        }
        return request.recipients().stream()
                .map(
                        asked -> {
                            BackupRecipient recipient = new BackupRecipient();
                            recipient.label = asked.label().trim();
                            recipient.publicKey = asked.publicKey().trim();
                            return recipient;
                        })
                .toList();
    }

    private static void require(String value, String complaint) {
        if (value == null || value.isBlank()) {
            throw new DestinationConflictException(complaint);
        }
    }
}
