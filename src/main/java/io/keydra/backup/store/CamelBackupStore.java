package io.keydra.backup.store;

import com.azure.storage.blob.models.BlobItem;
import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.entity.DestinationKind;
import io.keydra.backup.exception.BackupFailedException;
import io.keydra.tunnels.service.TunnelAccess;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.aws2.s3.AWS2S3Constants;
import org.apache.camel.component.aws2.s3.AWS2S3Operations;
import org.apache.camel.component.azure.storage.blob.BlobConstants;
import org.apache.camel.component.azure.storage.blob.BlobOperationsDefinition;
import org.apache.camel.component.file.FileEndpoint;
import org.apache.camel.component.file.FileOperations;
import org.apache.camel.component.file.GenericFileEndpoint;
import org.apache.camel.component.file.GenericFileOperations;
import org.apache.camel.component.file.remote.RemoteFileEndpoint;
import org.apache.camel.component.file.remote.RemoteFileOperations;
import org.apache.camel.component.file.remote.SftpRemoteFile;
import org.apache.camel.component.google.storage.GoogleCloudStorageConstants;
import org.apache.camel.component.google.storage.GoogleCloudStorageOperations;
import org.apache.commons.net.ftp.FTPFile;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

/**
 * Every destination, through Apache Camel.
 *
 * <p>One class rather than one per protocol, which is the point of using a framework at all: the
 * difference between a bucket and an SFTP drop is a string built in {@link DestinationUri}, and the
 * set of places a backup can go is the set of components on the classpath.
 *
 * <p>Sending is uniform — a producer, a body and a file name, for all five kinds. The other three
 * operations are not, because Camel's producers are not uniform about them: S3 publishes {@code
 * listObjects}, {@code getObject} and {@code deleteObject} as producer operations, while the file,
 * FTP and SFTP components put listing and fetching on the consumer side and expose them to code
 * through the file operations their endpoints create. Both are Camel's own API; only the shape
 * differs, and that difference is confined to this class.
 *
 * <p>A {@code CUSTOM} destination gets sending and nothing else. Which components can also be
 * listed and fetched from is a property of each component, and a destination whose address is
 * arbitrary cannot be promised more than the one operation every producer has.
 *
 * <p>All of it blocking, all of it on a worker thread. An upload is I/O that belongs there
 * whichever client performs it; the event loop simply must not be the thread waiting.
 */
@ApplicationScoped
public class CamelBackupStore {

    /** Camel's own name for a file's name, which most components that write files read. */
    private static final String FILE_NAME = Exchange.FILE_NAME;

    private final CamelContext camel;
    private final ProducerTemplate producer;
    private final DestinationUri uris;
    private final TunnelAccess access;
    private final Vertx vertx;

    @Inject
    CamelBackupStore(
            CamelContext camel,
            ProducerTemplate producer,
            DestinationUri uris,
            TunnelAccess access,
            Vertx vertx) {
        this.camel = camel;
        this.producer = producer;
        this.uris = uris;
        this.access = access;
        this.vertx = vertx;
    }

    /**
     * The URI to use for a destination, opening its tunnel first when it has one.
     *
     * <p>Resolved before the blocking work rather than inside it: opening a tunnel reads a row, and
     * a database read belongs on the Vert.x context rather than on the worker thread the upload
     * runs on.
     */
    private Uni<String> uriFor(BackupDestination destination) {
        if (destination.tunnelId == null) {
            return Uni.createFrom().item(() -> uris.of(destination));
        }
        DestinationUri.Address address = DestinationUri.tunnelledAddress(destination);
        if (address == null || address.host() == null) {
            return Uni.createFrom()
                    .failure(
                            new BackupFailedException(
                                    destination.name
                                            + " cannot be reached through a tunnel: a forwarded"
                                            + " port reaches a host and a port, and this kind is"
                                            + " named by an address in a certificate."));
        }
        return access.endpointFor(destination.tunnelId, address.host(), address.port())
                .map(endpoint -> uris.of(destination, endpoint));
    }

    /** Sends a staged file, under a name the destination will list it by. */
    public Uni<Void> put(BackupDestination destination, String name, Path file) {
        return uriFor(destination)
                .flatMap(
                        uri ->
                                offEventLoop(
                                        () -> {
                                            checkName(name);
                                            Map<String, Object> headers = new HashMap<>();
                                            // Both names, because the components disagree about
                                            // what a file is called
                                            // and neither minds a header it does not read.
                                            headers.put(FILE_NAME, name);
                                            String key =
                                                    DestinationUri.prefixOf(destination) + name;
                                            headers.put(AWS2S3Constants.KEY, key);
                                            headers.put(
                                                    AWS2S3Constants.CONTENT_LENGTH, sizeOf(file));
                                            headers.put(BlobConstants.BLOB_NAME, key);
                                            headers.put(
                                                    GoogleCloudStorageConstants.OBJECT_NAME, key);
                                            try {
                                                producer.sendBodyAndHeaders(
                                                        uri, file.toFile(), headers);
                                            } catch (RuntimeException refused) {
                                                throw failed("Could not write " + name, refused);
                                            }
                                            return null;
                                        }));
    }

    /**
     * What is there, newest first.
     *
     * @param prefix only names starting with this, or null for everything — retention applies to
     *     the files one schedule writes, so it has to be able to ask about those alone
     */
    public Uni<List<StoredBackup>> list(BackupDestination destination, String prefix) {
        return uriFor(destination)
                .flatMap(
                        uri ->
                                offEventLoop(
                                        () -> {
                                            requireReadable(destination, "listed");
                                            List<StoredBackup> found =
                                                    isObjectStore(destination)
                                                            ? listObjects(uri, destination, prefix)
                                                            : listDirectory(
                                                                    uri, destination, prefix);
                                            return found.stream()
                                                    .sorted(
                                                            Comparator.comparing(
                                                                            StoredBackup
                                                                                    ::modifiedAt,
                                                                            Comparator.nullsLast(
                                                                                    Comparator
                                                                                            .reverseOrder()))
                                                                    .thenComparing(
                                                                            StoredBackup::name))
                                                    .toList();
                                        }));
    }

    /** Fetches one back into a local file, which is what a restore reads. */
    public Uni<Void> get(BackupDestination destination, String name, Path into) {
        return uriFor(destination)
                .flatMap(
                        uri ->
                                offEventLoop(
                                        () -> {
                                            checkName(name);
                                            requireReadable(destination, "read back");
                                            if (isObjectStore(destination)) {
                                                fetchObject(uri, destination, name, into);
                                            } else {
                                                fetchFile(uri, destination, name, into);
                                            }
                                            return null;
                                        }));
    }

    public Uni<Void> delete(BackupDestination destination, String name) {
        return uriFor(destination)
                .flatMap(
                        uri ->
                                offEventLoop(
                                        () -> {
                                            checkName(name);
                                            requireReadable(destination, "cleaned up");
                                            if (isObjectStore(destination)) {
                                                deleteObject(uri, destination, name);
                                            } else {
                                                withFileOperations(
                                                        uri,
                                                        destination,
                                                        (operations, directory) ->
                                                                operations.deleteFile(
                                                                        fullPath(directory, name)));
                                            }
                                            return null;
                                        }));
    }

    /**
     * Forgets a destination's endpoints, so the next use is built from what is stored now.
     *
     * <p>Camel caches endpoints by URI, and a URI carries the credentials — so an edited password
     * would otherwise leave the old endpoint, and the old password, in use until a restart.
     */
    public void forget(Long destinationId) {
        // Nothing to match against but the whole set: the URI of the destination as it was is
        // exactly what is no longer known. Endpoints are cheap to rebuild and this happens
        // only when somebody saves a form.
        try {
            camel.removeEndpoints("*");
        } catch (Exception stubborn) {
            // Nothing to do about an endpoint that will not close, and nothing worth failing
            // a save over: the next use builds a new one either way.
        }
        camel.getRegistry().unbind("keydra-destination-" + destinationId + "-key");
    }

    // --- Object stores, through the producer operations they publish --------

    /** The three that answer in objects rather than in directories. */
    private static boolean isObjectStore(BackupDestination destination) {
        return destination.kind == DestinationKind.S3
                || destination.kind == DestinationKind.AZURE_BLOB
                || destination.kind == DestinationKind.GCS;
    }

    /**
     * What is in the container, whichever cloud it is.
     *
     * <p>Three components, one shape: name the operation in a header, name the prefix in another,
     * read the list off the body. Only the header names and the type of the answer differ, which is
     * the whole of what adding a third cloud cost.
     */
    private List<StoredBackup> listObjects(
            String uri, BackupDestination destination, String prefix) {
        String base = DestinationUri.prefixOf(destination);
        String full = prefix == null ? base : base + prefix;
        Object body = producer.requestBodyAndHeaders(uri, null, listHeaders(destination, full));
        return describeObjects(body, base);
    }

    private static Map<String, Object> listHeaders(BackupDestination destination, String prefix) {
        return switch (destination.kind) {
            case AZURE_BLOB ->
                    Map.of(
                            BlobConstants.BLOB_OPERATION,
                            BlobOperationsDefinition.listBlobs,
                            BlobConstants.PREFIX,
                            prefix);
            case GCS ->
                    Map.of(
                            GoogleCloudStorageConstants.OPERATION,
                            GoogleCloudStorageOperations.listObjects,
                            GoogleCloudStorageConstants.PREFIX_NAME,
                            prefix);
            default ->
                    Map.of(
                            AWS2S3Constants.S3_OPERATION,
                            AWS2S3Operations.listObjects,
                            AWS2S3Constants.PREFIX,
                            prefix);
        };
    }

    /** Reads a listing off whichever answer the component gave. */
    private static List<StoredBackup> describeObjects(Object body, String base) {
        if (body instanceof ListObjectsV2Response response) {
            return response.contents().stream()
                    .map(
                            object ->
                                    new StoredBackup(
                                            strip(base, object.key()),
                                            object.size(),
                                            object.lastModified()))
                    .toList();
        }
        if (body instanceof List<?> items) {
            List<StoredBackup> found = new ArrayList<>();
            for (Object item : items) {
                StoredBackup described = describeObject(item, base);
                if (described != null) {
                    found.add(described);
                }
            }
            return List.copyOf(found);
        }
        throw new BackupFailedException("The destination answered something unexpected");
    }

    private static StoredBackup describeObject(Object item, String base) {
        if (item instanceof BlobItem blob) {
            return new StoredBackup(
                    strip(base, blob.getName()),
                    blob.getProperties() == null || blob.getProperties().getContentLength() == null
                            ? 0
                            : blob.getProperties().getContentLength(),
                    blob.getProperties() == null || blob.getProperties().getLastModified() == null
                            ? null
                            : blob.getProperties().getLastModified().toInstant());
        }
        if (item instanceof com.google.cloud.storage.Blob blob) {
            return new StoredBackup(
                    strip(base, blob.getName()),
                    blob.getSize() == null ? 0 : blob.getSize(),
                    blob.getUpdateTimeOffsetDateTime() == null
                            ? null
                            : blob.getUpdateTimeOffsetDateTime().toInstant());
        }
        return null;
    }

    private void fetchObject(String uri, BackupDestination destination, String name, Path into) {
        String key = DestinationUri.prefixOf(destination) + name;
        Object body = producer.requestBodyAndHeaders(uri, null, fetchHeaders(destination, key));
        try (InputStream stream = asStream(body)) {
            Files.copy(stream, into, StandardCopyOption.REPLACE_EXISTING);
        } catch (BackupFailedException already) {
            throw already;
        } catch (Exception unreadable) {
            throw failed("Could not fetch " + name, unreadable);
        }
    }

    private static Map<String, Object> fetchHeaders(BackupDestination destination, String key) {
        return switch (destination.kind) {
            case AZURE_BLOB ->
                    Map.of(
                            BlobConstants.BLOB_OPERATION,
                            BlobOperationsDefinition.getBlob,
                            BlobConstants.BLOB_NAME,
                            key);
            case GCS ->
                    Map.of(
                            GoogleCloudStorageConstants.OPERATION,
                            GoogleCloudStorageOperations.getObject,
                            GoogleCloudStorageConstants.OBJECT_NAME,
                            key);
            default ->
                    Map.of(
                            AWS2S3Constants.S3_OPERATION,
                            AWS2S3Operations.getObject,
                            AWS2S3Constants.KEY,
                            key);
        };
    }

    /**
     * The bytes, whichever wrapper the component handed back.
     *
     * <p>S3 and Azure answer with a stream; Google answers with its own {@code Blob}, which has to
     * be asked for its content. The alternative — a type converter registered for it — would put
     * the same three lines somewhere less obvious.
     */
    private static InputStream asStream(Object body) {
        if (body instanceof InputStream stream) {
            return stream;
        }
        if (body instanceof com.google.cloud.storage.Blob blob) {
            return new java.io.ByteArrayInputStream(blob.getContent());
        }
        if (body instanceof byte[] bytes) {
            return new java.io.ByteArrayInputStream(bytes);
        }
        throw new BackupFailedException("The destination answered something unexpected");
    }

    private void deleteObject(String uri, BackupDestination destination, String name) {
        String key = DestinationUri.prefixOf(destination) + name;
        Map<String, Object> headers =
                switch (destination.kind) {
                    case AZURE_BLOB ->
                            Map.of(
                                    BlobConstants.BLOB_OPERATION,
                                    BlobOperationsDefinition.deleteBlob,
                                    BlobConstants.BLOB_NAME,
                                    key);
                    case GCS ->
                            Map.of(
                                    GoogleCloudStorageConstants.OPERATION,
                                    GoogleCloudStorageOperations.deleteObject,
                                    GoogleCloudStorageConstants.OBJECT_NAME,
                                    key);
                    default ->
                            Map.of(
                                    AWS2S3Constants.S3_OPERATION,
                                    AWS2S3Operations.deleteObject,
                                    AWS2S3Constants.KEY,
                                    key);
                };
        producer.sendBodyAndHeaders(uri, null, headers);
    }

    // --- File, FTP and SFTP, through the operations their endpoints create ---

    private List<StoredBackup> listDirectory(
            String uri, BackupDestination destination, String prefix) {
        if (destination.kind == DestinationKind.LOCAL) {
            return listLocally(uris.directoryOf(destination), prefix);
        }
        return withFileOperations(
                uri,
                destination,
                (operations, directory) -> {
                    Object[] entries;
                    try {
                        entries = operations.listFiles(directory);
                    } catch (Exception missing) {
                        // A directory nothing has been written to yet is not a failure; it is
                        // a destination waiting for its first backup. A destination that
                        // cannot be reached at all has already failed in connect() above, so
                        // what is left here is overwhelmingly a path that is not there yet.
                        return List.of();
                    }
                    List<StoredBackup> found = new ArrayList<>();
                    for (Object entry : entries == null ? new Object[0] : entries) {
                        StoredBackup described = describe(entry);
                        if (described != null
                                && (prefix == null || described.name().startsWith(prefix))) {
                            found.add(described);
                        }
                    }
                    return List.copyOf(found);
                });
    }

    /**
     * A local directory, read with the JDK.
     *
     * <p>Not for want of trying to use Camel. The file component's operations have no listing at
     * all — {@code FileOperations.listFiles} returns null, because Camel's file consumer scans the
     * directory itself rather than asking the operations for it. The FTP and SFTP operations do
     * implement it, which is why those go through Camel and this does not.
     *
     * <p>Writing still goes through the producer, so a local destination and a bucket are written
     * by the same call. Only reading a directory that needs no client at all is done here.
     */
    private static List<StoredBackup> listLocally(String directory, String prefix) {
        Path path = Path.of(directory);
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(path)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> describe(file.toFile()))
                    .filter(java.util.Objects::nonNull)
                    .filter(found -> prefix == null || found.name().startsWith(prefix))
                    .toList();
        } catch (Exception unreadable) {
            throw failed("Could not read " + directory, unreadable);
        }
    }

    private void fetchFile(String uri, BackupDestination destination, String name, Path into) {
        if (destination.kind == DestinationKind.LOCAL) {
            // The local file operations have no retrieve: Camel's file consumer reads the file
            // itself rather than asking the operations for it, so retrieveFile is a no-op that
            // answers true. Copying the file is what "fetch it" means for a directory.
            Path from = Path.of(uris.directoryOf(destination), name);
            try {
                Files.copy(from, into, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception unreadable) {
                throw failed("Could not read " + name, unreadable);
            }
            return;
        }

        withFileOperations(
                uri,
                destination,
                (operations, directory) -> {
                    Endpoint endpoint = camel.getEndpoint(uri);
                    Exchange exchange = endpoint.createExchange(ExchangePattern.InOnly);
                    String path = fullPath(directory, name);
                    try {
                        if (!operations.retrieveFile(path, exchange, 0)) {
                            throw new BackupFailedException(name + " is not there");
                        }
                        try (InputStream stream = exchange.getIn().getBody(InputStream.class)) {
                            if (stream == null) {
                                throw new BackupFailedException(
                                        "The destination sent nothing for " + name);
                            }
                            Files.copy(stream, into, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (BackupFailedException already) {
                        throw already;
                    } catch (Exception unreadable) {
                        throw failed("Could not fetch " + name, unreadable);
                    } finally {
                        operations.releaseRetrievedFileResources(exchange);
                    }
                    return null;
                });
    }

    /**
     * Opens the endpoint's file operations, does the work, and closes them.
     *
     * <p>A connection per operation rather than a pool. A backup runs once a night and a restore
     * once a year; a connection kept open between them is one a firewall dropped long ago, and the
     * failure would arrive at exactly the moment the feature exists for.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T withFileOperations(String uri, BackupDestination destination, FileWork<T> work) {
        Endpoint endpoint = camel.getEndpoint(uri);
        String directory = uris.directoryOf(destination);

        if (endpoint instanceof FileEndpoint local) {
            FileOperations operations = new FileOperations(local);
            operations.setEndpoint(local);
            return work.run((GenericFileOperations) operations, directory);
        }
        if (endpoint instanceof RemoteFileEndpoint<?> remote) {
            RemoteFileOperations operations;
            try {
                operations = remote.createRemoteFileOperations();
                operations.setEndpoint((GenericFileEndpoint) remote);
                operations.connect(remote.getConfiguration(), null);
            } catch (Exception unreachable) {
                throw failed("Could not reach " + destination.location, unreachable);
            }
            try {
                return work.run((GenericFileOperations) operations, directory);
            } catch (BackupFailedException already) {
                throw already;
            } catch (Exception failure) {
                throw failed("Could not reach " + destination.location, failure);
            } finally {
                try {
                    operations.disconnect();
                } catch (Exception hangingUp) {
                    // The work either succeeded or already threw; a failure while hanging up
                    // must not replace either answer.
                }
            }
        }
        throw new BackupFailedException(
                "A " + destination.kind + " destination cannot be read back");
    }

    /** One directory entry, whichever component produced it. */
    private static StoredBackup describe(Object entry) {
        if (entry instanceof File file) {
            return file.isDirectory()
                    ? null
                    : new StoredBackup(
                            file.getName(),
                            file.length(),
                            Instant.ofEpochMilli(file.lastModified()));
        }
        if (entry instanceof FTPFile file) {
            return file.isFile()
                    ? new StoredBackup(file.getName(), file.getSize(), modified(file))
                    : null;
        }
        if (entry instanceof SftpRemoteFile<?> remote) {
            // Camel's own view of an SFTP directory entry, which is why nothing here has to
            // know which SSH library the component is built on.
            return remote.isDirectory()
                    ? null
                    : new StoredBackup(
                            remote.getFilename(),
                            remote.getFileLength(),
                            Instant.ofEpochMilli(remote.getLastModified()));
        }
        return null;
    }

    private static Instant modified(FTPFile file) {
        if (file.getTimestampInstant() != null) {
            return file.getTimestampInstant();
        }
        return file.getTimestamp() == null ? null : file.getTimestamp().toInstant();
    }

    // --- Small things ------------------------------------------------------

    private static void requireReadable(BackupDestination destination, String verb) {
        if (!DestinationUri.readable(destination)) {
            throw new BackupFailedException(
                    destination.name
                            + " is a custom endpoint, which can be written to and not "
                            + verb
                            + ". Retention and restoring need a destination Keydra can also"
                            + " read.");
        }
    }

    /** The name is Keydra's own, but it also arrives from a restore request. */
    private static void checkName(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new BackupFailedException("A backup name cannot contain a path");
        }
    }

    private static String fullPath(String directory, String name) {
        return directory == null || directory.isEmpty() ? name : directory + "/" + name;
    }

    private static String strip(String prefix, String key) {
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (Exception unreadable) {
            return 0;
        }
    }

    private static BackupFailedException failed(String what, Throwable failure) {
        return failure instanceof BackupFailedException already
                ? already
                : new BackupFailedException(what, failure);
    }

    /**
     * Runs blocking work somewhere it is allowed to block, and comes back.
     *
     * <p>{@code executeBlocking} rather than {@code runSubscriptionOn}, which is the same lesson
     * the SSH tunnels already carry: {@code runSubscriptionOn} leaves everything downstream on the
     * worker thread, and downstream of a backup is Hibernate Reactive — which refuses to run
     * anywhere but its own context. Vert.x completes this back on the calling context, so the
     * blocking part is the only part that leaves it.
     */
    private <T> Uni<T> offEventLoop(Supplier<T> work) {
        return Uni.createFrom()
                .completionStage(() -> vertx.executeBlocking(work::get, false).toCompletionStage());
    }

    /** Work done with open file operations, on a worker thread. */
    @FunctionalInterface
    private interface FileWork<T> {
        T run(GenericFileOperations<?> operations, String directory);
    }
}
