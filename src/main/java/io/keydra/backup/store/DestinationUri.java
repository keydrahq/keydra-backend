package io.keydra.backup.store;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.exception.BackupFailedException;
import io.keydra.tunnels.TunnelEndpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.camel.CamelContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Turns a destination row into the Camel endpoint URI that reaches it.
 *
 * <p>This is where a kind actually lives. Everything above deals in destinations and file names;
 * everything below is Camel, and the only thing that differs between a bucket and an SFTP drop is
 * the string built here. Adding a kind with a form of its own is adding a case; adding one without
 * is already possible, because {@code CUSTOM} takes the address directly.
 *
 * <p>Secrets go in wrapped in {@code RAW(...)}, which stops Camel from URL-decoding a password that
 * happens to contain a {@code %} — and Camel sanitises those parameters out of anything it logs, so
 * the URI is safe to appear in an error. Nothing here logs one anyway.
 */
@ApplicationScoped
public class DestinationUri {

    /** Where a local destination is confined to. A path in a row is not a way out of it. */
    private final Path localRoot;

    private final CamelContext camel;

    @Inject
    DestinationUri(
            CamelContext camel,
            @ConfigProperty(name = "keydra.backup.local-root", defaultValue = "backups")
                    String localRoot) {
        this.camel = camel;
        this.localRoot = Path.of(localRoot).toAbsolutePath().normalize();
    }

    /** The endpoint URI for a destination reached directly. */
    public String of(BackupDestination destination) {
        return of(destination, null);
    }

    /**
     * The endpoint URI for a destination, reached through a tunnel where one applies.
     *
     * @param through a local address that leads to the destination, or null for a direct one
     */
    public String of(BackupDestination destination, TunnelEndpoint through) {
        return switch (destination.kind) {
            case LOCAL -> local(destination);
            case SFTP -> remote(destination, "sftp", 22, through);
            case FTP -> remote(destination, destination.tls ? "ftps" : "ftp", 21, through);
            case S3 -> bucket(destination, through);
            case AZURE_BLOB -> container(destination);
            case GCS -> googleBucket(destination);
            case CUSTOM -> raw(destination);
        };
    }

    /**
     * Where a tunnel would have to lead for this destination, or null when one cannot help.
     *
     * <p>A tunnel is a forwarded TCP port, so it reaches anything named by a host and a port: an
     * SFTP drop, an FTP server, an S3-compatible store with an endpoint of its own. It cannot reach
     * the public clouds — their addresses are names in a certificate, and forwarding one to {@code
     * 127.0.0.1} is a TLS failure rather than a connection. Saying so here is what lets the
     * interface refuse the combination instead of the connection failing later for a reason nobody
     * can read off the row.
     */
    public static Address tunnelledAddress(BackupDestination destination) {
        return switch (destination.kind) {
            case SFTP ->
                    new Address(
                            destination.location, destination.port == null ? 22 : destination.port);
            case FTP ->
                    new Address(
                            destination.location, destination.port == null ? 21 : destination.port);
            case S3 -> {
                if (destination.endpoint == null || destination.endpoint.isBlank()) {
                    yield null;
                }
                URI endpoint = URI.create(destination.endpoint);
                yield new Address(
                        endpoint.getHost(),
                        endpoint.getPort() > 0
                                ? endpoint.getPort()
                                : ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80));
            }
            default -> null;
        };
    }

    /** A host and a port on the far side of a tunnel. */
    public record Address(String host, int port) {}

    /**
     * Whether this kind can be listed, fetched from and deleted from.
     *
     * <p>False only for {@code CUSTOM}: a producer sends, and which components can also list and
     * fetch is a property of each component. The six with forms all can — file, FTP and SFTP
     * through the file operations their endpoints expose, and the three object stores through the
     * producer operations they publish.
     */
    public static boolean readable(BackupDestination destination) {
        return destination.kind != io.keydra.backup.entity.DestinationKind.CUSTOM;
    }

    // --- One per kind ------------------------------------------------------

    private String local(BackupDestination destination) {
        Path resolved =
                destination.path == null || destination.path.isBlank()
                        ? localRoot
                        : localRoot.resolve(destination.path).normalize();
        if (!resolved.startsWith(localRoot)) {
            throw new BackupFailedException(
                    "A local destination cannot point outside " + localRoot);
        }
        return "file://" + resolved + "?autoCreate=true";
    }

    private String remote(
            BackupDestination destination, String scheme, int defaultPort, TunnelEndpoint through) {
        String host = required(destination.location, scheme + " needs a host");
        String user = required(destination.accessKey, scheme + " needs a username");
        int port = destination.port == null ? defaultPort : destination.port;
        if (through != null) {
            // Everything above connects to a local address and never learns it is not the
            // real one, which is the whole point of a forward.
            host = through.host();
            port = through.port();
        }

        List<String> options = new ArrayList<>();
        options.add("autoCreate=true");
        // The body arrives as a stream rather than as a byte array, so fetching a backup does
        // not put the backup in memory.
        options.add("streamDownload=true");
        if ("sftp".equals(scheme)) {
            // One connection for the whole operation rather than a directory change per
            // segment: stepwise fails against servers that do not allow a relative cd, and
            // costs a round trip per path element on the ones that do.
            options.add("stepwise=false");
            if (present(destination.privateKey)) {
                options.add("privateKey=#" + bindKey(destination));
            }
            if (present(destination.passphrase)) {
                options.add("privateKeyPassphrase=RAW(" + destination.passphrase + ")");
            }
        } else {
            // Binary, or a compressed file arrives corrupted; passive, or the data connection
            // is one the server opens back through the firewall.
            options.add("binary=true");
            options.add("passiveMode=true");
        }
        if (present(destination.secretKey)) {
            options.add("password=RAW(" + destination.secretKey + ")");
        }

        String directory =
                destination.path == null || destination.path.isBlank()
                        ? ""
                        : "/" + trimSlashes(destination.path);
        return scheme
                + "://"
                + user
                + "@"
                + host
                + ":"
                + port
                + directory
                + "?"
                + String.join("&", options);
    }

    private String bucket(BackupDestination destination, TunnelEndpoint through) {
        String name = required(destination.location, "An S3 destination needs a bucket");
        List<String> options = new ArrayList<>();
        options.add("region=" + (present(destination.region) ? destination.region : "us-east-1"));
        if (present(destination.accessKey)) {
            options.add("accessKey=RAW(" + destination.accessKey + ")");
            options.add(
                    "secretKey=RAW("
                            + (destination.secretKey == null ? "" : destination.secretKey)
                            + ")");
        } else {
            // No key means the machine's own credentials, which is what an instance role or a
            // mounted service account is for. A deployment that has one should not have to
            // paste a long-lived key to use it.
            options.add("useDefaultCredentialsProvider=true");
        }
        if (present(destination.endpoint)) {
            options.add("overrideEndpoint=true");
            options.add(
                    "uriEndpointOverride="
                            + (through == null
                                    ? destination.endpoint
                                    // The scheme is kept and the address replaced: a MinIO
                                    // behind a jump host is still http or https, on a local port.
                                    : URI.create(destination.endpoint).getScheme()
                                            + "://"
                                            + through.host()
                                            + ":"
                                            + through.port()));
        }
        if (destination.pathStyle) {
            // On for everything that is not AWS: a MinIO on an IP address cannot be reached
            // as bucket.10.0.0.4, and the failure without this reads as a DNS problem.
            options.add("forcePathStyle=true");
        }
        return "aws2-s3://" + name + "?" + String.join("&", options);
    }

    /**
     * Azure Blob Storage.
     *
     * <p>The account is the username of this protocol and the container is the bucket, so they sit
     * in the same two columns everything else uses. The credential type has to be said explicitly:
     * the component's default looks for the environment's own Azure identity, which is right in
     * Azure and silent everywhere else.
     */
    private static String container(BackupDestination destination) {
        String account = required(destination.accessKey, "Azure Blob needs a storage account");
        String name = required(destination.location, "Azure Blob needs a container");
        List<String> options = new ArrayList<>();
        if (present(destination.secretKey)) {
            options.add("accessKey=RAW(" + destination.secretKey + ")");
            options.add("credentialType=SHARED_ACCOUNT_KEY");
        } else {
            // No key means whatever identity the machine is running as, which is what a
            // managed identity in Azure is for.
            options.add("credentialType=AZURE_IDENTITY");
        }
        return "azure-storage-blob://" + account + "/" + name + "?" + String.join("&", options);
    }

    /**
     * Google Cloud Storage.
     *
     * <p>The service account key is a JSON document rather than a password, and the component wants
     * a place to read one from — a file, a classpath entry, a URL. None of those is somewhere a
     * secret should be put, so the client is built from the stored JSON in memory and bound in the
     * registry, and the URI names the client instead of the key.
     */
    private String googleBucket(BackupDestination destination) {
        String name = required(destination.location, "Google Cloud Storage needs a bucket");
        if (!present(destination.secretKey)) {
            // Application Default Credentials: the metadata server in GKE, or the file a
            // developer's gcloud login left behind.
            return "google-storage://" + name + "?autoCreateBucket=false";
        }
        return "google-storage://"
                + name
                + "?autoCreateBucket=false&storageClient=#"
                + bindGoogleClient(destination);
    }

    /**
     * Builds a Google client from the stored key and keeps it in the registry.
     *
     * <p>Named after the destination, so editing one replaces its client rather than leaving the
     * old credentials in use — the same rule the endpoints themselves follow.
     */
    private String bindGoogleClient(BackupDestination destination) {
        String name = "keydra-destination-" + destination.id + "-gcs";
        try {
            GoogleCredentials credentials =
                    GoogleCredentials.fromStream(
                            new java.io.ByteArrayInputStream(
                                    destination.secretKey.getBytes(StandardCharsets.UTF_8)));
            camel.getRegistry()
                    .bind(
                            name,
                            Storage.class,
                            StorageOptions.newBuilder()
                                    .setCredentials(credentials)
                                    .build()
                                    .getService());
        } catch (Exception unusable) {
            throw new BackupFailedException(
                    "The stored Google service account key could not be read", unusable);
        }
        return name;
    }

    private static String raw(BackupDestination destination) {
        String uri = required(destination.location, "This destination needs an endpoint address");
        if (!uri.matches("^[a-zA-Z0-9+.-]+:.*")) {
            throw new BackupFailedException(
                    "'"
                            + uri
                            + "' is not an endpoint address; it needs a scheme and a colon, like"
                            + " jms:queue:backups");
        }
        return uri;
    }

    /**
     * The directory the file operations work in, for the kinds that have one.
     *
     * <p>Camel's file operations take whole paths rather than names relative to the endpoint, so
     * the directory the URI already carries has to be said again when they are called directly.
     */
    public String directoryOf(BackupDestination destination) {
        return switch (destination.kind) {
            case LOCAL -> local(destination).substring("file://".length()).split("\\?")[0];
            case SFTP, FTP ->
                    destination.path == null || destination.path.isBlank()
                            ? ""
                            : trimSlashes(destination.path);
            default -> "";
        };
    }

    /** The prefix every S3 key under this destination carries, ending in a slash or empty. */
    public static String prefixOf(BackupDestination destination) {
        if (destination.path == null || destination.path.isBlank()) {
            return "";
        }
        String trimmed = trimSlashes(destination.path);
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }

    /**
     * Puts an SFTP private key in the registry, because a URI cannot carry one.
     *
     * <p>Camel's {@code privateKey} option takes a byte array looked up by name, which is the right
     * shape anyway: a PEM block in a query string would be unreadable and would end up in every
     * error message that quoted the URI.
     */
    private String bindKey(BackupDestination destination) {
        String name = "keydra-destination-" + destination.id + "-key";
        camel.getRegistry()
                .bind(name, byte[].class, destination.privateKey.getBytes(StandardCharsets.UTF_8));
        return name;
    }

    private static String required(String value, String complaint) {
        if (!present(value)) {
            throw new BackupFailedException(complaint);
        }
        return value;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
