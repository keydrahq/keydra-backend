package io.keydra.backup.mapper;

import io.keydra.backup.dto.BackupDtos.DestinationRequest;
import io.keydra.backup.dto.BackupDtos.DestinationSummary;
import io.keydra.backup.dto.BackupDtos.RecipientSummary;
import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.entity.BackupRecipient;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

/**
 * Translates between a destination row and its wire shape.
 *
 * <p>Generated, like the connection mapper and for the same reason: field-by-field copying is
 * exactly the code that rots when a field is added and somebody forgets a line, and the build fails
 * on an unmapped target rather than letting one arrive as null.
 *
 * <p>Three rules are not plain copies and are written out below: the secrets become booleans so a
 * stored credential cannot be serialised by accident, the same secrets follow keep-or-clear on the
 * way in, and the one-line description is derived from whichever fields the kind actually uses.
 */
@Mapper(componentModel = "jakarta")
public interface BackupDestinationMapper {

    @Mapping(target = "hasSecret", source = "secretKey", qualifiedByName = "isPresent")
    @Mapping(target = "hasPrivateKey", source = "privateKey", qualifiedByName = "isPresent")
    @Mapping(target = "describedAs", source = "destination", qualifiedByName = "describe")
    // Whether one is stored, never what it is — the same rule as every other secret here.
    @Mapping(target = "encrypts", expression = "java(destination.encrypts())")
    DestinationSummary toSummary(BackupDestination destination);

    /** The keys, as the list a page shows: a name and the half that only encrypts. */
    RecipientSummary toSummary(BackupRecipient recipient);

    /**
     * Applies a request onto an existing destination.
     *
     * <p>The id belongs to persistence, the three flags have defaults an absent value should not
     * overwrite, and the secrets have their own rule — so all of them are excluded here and set
     * below.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "pathStyle", ignore = true)
    @Mapping(target = "tls", ignore = true)
    @Mapping(target = "secretKey", ignore = true)
    @Mapping(target = "privateKey", ignore = true)
    @Mapping(target = "passphrase", ignore = true)
    @Mapping(target = "encryptionPassphrase", ignore = true)
    // Not a field of the row at all: the recipients are their own table, and the service writes
    // them beside the destination rather than through it.
    @Mapping(target = "recipients", ignore = true)
    void apply(DestinationRequest request, @MappingTarget BackupDestination destination);

    /**
     * The flags, where absent means "leave it as it was".
     *
     * <p>A request that omits {@code enabled} is a request about something else; unboxing a null
     * onto a primitive would turn every partial edit into a crash, and defaulting it to false would
     * turn every partial edit into a destination that quietly stopped being used.
     */
    @AfterMapping
    default void applyFlags(
            DestinationRequest request, @MappingTarget BackupDestination destination) {
        if (request.enabled() != null) {
            destination.enabled = request.enabled();
        }
        if (request.pathStyle() != null) {
            destination.pathStyle = request.pathStyle();
        }
        if (request.tls() != null) {
            destination.tls = request.tls();
        }
    }

    /**
     * Secrets: absent means keep, empty means clear.
     *
     * <p>The same rule as a target's password, and for the same reason. The API never returns a
     * stored secret, so an edit form arrives with the field empty; treating that as "clear it"
     * would drop the credential every time somebody corrected a label.
     */
    @AfterMapping
    default void applySecrets(
            DestinationRequest request, @MappingTarget BackupDestination destination) {
        if (request.secretKey() != null) {
            destination.secretKey = request.secretKey().isEmpty() ? null : request.secretKey();
        }
        if (request.privateKey() != null) {
            destination.privateKey = request.privateKey().isEmpty() ? null : request.privateKey();
        }
        if (request.passphrase() != null) {
            destination.passphrase = request.passphrase().isEmpty() ? null : request.passphrase();
        }
        if (request.encryptionPassphrase() != null) {
            destination.encryptionPassphrase =
                    request.encryptionPassphrase().isEmpty()
                            ? null
                            : request.encryptionPassphrase();
        }
    }

    @Named("isPresent")
    static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Where this points, in one line.
     *
     * <p>A list of destinations is read to answer "which of these is the production bucket", and a
     * row showing only a name and a kind cannot answer it. Built from whichever fields the kind
     * actually uses, so nothing shows an empty host beside a bucket.
     */
    @Named("describe")
    static String describe(BackupDestination destination) {
        String path =
                destination.path == null || destination.path.isBlank() ? "" : destination.path;
        return switch (destination.kind) {
            case LOCAL -> path.isEmpty() ? "the backup directory" : path;
            case S3 -> "s3://" + orUnset(destination.location) + (path.isEmpty() ? "" : "/" + path);
            case SFTP -> "sftp://" + host(destination) + "/" + path;
            case FTP -> (destination.tls ? "ftps://" : "ftp://") + host(destination) + "/" + path;
            case AZURE_BLOB ->
                    "https://"
                            + orUnset(destination.accessKey)
                            + ".blob.core.windows.net/"
                            + orUnset(destination.location)
                            + (path.isEmpty() ? "" : "/" + path);
            case GCS ->
                    "gs://" + orUnset(destination.location) + (path.isEmpty() ? "" : "/" + path);
            // Already an address, and the one thing worth hiding is a password inside it.
            case CUSTOM -> sanitised(destination.location);
        };
    }

    private static String host(BackupDestination destination) {
        String user =
                destination.accessKey == null || destination.accessKey.isBlank()
                        ? ""
                        : destination.accessKey + "@";
        String port = destination.port == null ? "" : ":" + destination.port;
        return user + orUnset(destination.location) + port;
    }

    /**
     * A Camel URI with anything that looks like a credential taken out.
     *
     * <p>A URI typed by hand can carry a password in a query parameter, and this string is shown in
     * a list. Camel sanitises its own logging the same way; this is that rule applied to what
     * Keydra puts on a screen.
     */
    private static String sanitised(String uri) {
        return uri == null
                ? "(not set)"
                : uri.replaceAll(
                        "(?i)([?&](password|passphrase|secretkey|accesskey|token|secret)=)[^&]*",
                        "$1******");
    }

    private static String orUnset(String value) {
        return value == null || value.isBlank() ? "(not set)" : value;
    }
}
