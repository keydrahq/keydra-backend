package io.keydra.backup.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Somewhere backups are sent.
 *
 * <p>A thing rather than a string on each schedule. Otherwise every backup job carries its own copy
 * of a bucket name and a secret key, and rotating the key means editing all of them — the same
 * argument that made connection profiles rows instead of a list in a configuration file.
 *
 * <p>The columns are deliberately generic. A bucket and a host are both "where", a prefix and a
 * remote directory are both "which part of it", and a kind that needed a fifth field of its own
 * would be a kind that should carry it in its own settings rather than widening this table for
 * everybody.
 */
@Entity
@Table(
        name = "backup_destination",
        indexes = {
            @Index(name = "idx_backup_destination_name", columnList = "name", unique = true)
        })
public class BackupDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "backup_destination_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    @Enumerated(EnumType.STRING)
    // Spelled out so no check constraint listing today's kinds is generated: it is written
    // once and never widened, so adding a kind would make every insert fail.
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public DestinationKind kind = DestinationKind.LOCAL;

    @Column(nullable = false)
    public boolean enabled = true;

    /**
     * Where it is: a bucket for S3, a host for SFTP and FTP, unused for a local directory.
     *
     * <p>One column for the two because they are the same question asked of different protocols,
     * and a table with {@code bucket} and {@code host} beside each other would have one of them
     * null in every row.
     */
    @Column(length = 300)
    public String location;

    /** The part of it backups go in: a key prefix, a remote directory, a local path. */
    @Column(length = 500)
    public String path;

    /** SFTP and FTP. Null means the protocol's own. */
    @Column(name = "port_number")
    public Integer port;

    /**
     * The S3 endpoint, for everything that speaks the API without being AWS.
     *
     * <p>Null means AWS itself. Present means MinIO, R2, Spaces or a company's own — which is the
     * majority of the buckets this will ever write to.
     */
    @Column(name = "endpoint_url", length = 500)
    public String endpoint;

    /** The S3 region. Sent even to stores that do not have regions, which expect one anyway. */
    @Column(length = 64)
    public String region;

    /**
     * Whether S3 addresses the bucket by path rather than by subdomain.
     *
     * <p>Off for AWS and on for nearly everything else: a MinIO on an IP address cannot be reached
     * as {@code bucket.10.0.0.4}, and the failure without this reads as a DNS problem.
     */
    @Column(name = "path_style", nullable = false)
    public boolean pathStyle = false;

    /** An access key id for S3, a username for SFTP and FTP. */
    @Column(name = "access_key", length = 300)
    public String accessKey;

    /**
     * Encrypted at rest, with the same mechanism as a target's password.
     *
     * <p>Never returned by the API — the destination page says whether one is set and nothing more.
     */
    @Column(name = "secret_key", length = 2000)
    @Convert(converter = EncryptedStringConverter.class)
    public String secretKey;

    /** An SFTP private key, for the destinations that prefer one to a password. */
    @Column(name = "private_key", length = 8000)
    @Convert(converter = EncryptedStringConverter.class)
    public String privateKey;

    @Column(length = 2000)
    @Convert(converter = EncryptedStringConverter.class)
    public String passphrase;

    /** FTPS, for an FTP server that offers it. */
    @Column(name = "use_tls", nullable = false)
    public boolean tls = false;

    /**
     * What the backups sent here are encrypted with, if anything.
     *
     * <p>Encrypted at rest like every other secret, and never returned — which is also the thing
     * that has to be said out loud: lose it and the backups are gone. Keydra cannot recover them,
     * and that is the entire point of them being encrypted.
     *
     * <p>On the destination rather than on each job, because it is a property of the place: two
     * schedules writing to the same bucket must produce files that can be read the same way.
     */
    @Column(name = "encryption_passphrase", length = 2048)
    @Convert(converter = EncryptedStringConverter.class)
    public String encryptionPassphrase;

    /**
     * The keys backups sent here are encrypted to.
     *
     * <p>Not a column and not a mapped collection: {@code BackupRepository} fills it in on every
     * read and writes it on every save. A mapped collection here would be a lazy load on a reactive
     * session, which is the thing this backend does not do; and the list is small, always read
     * whole and always written whole, so there is nothing a mapping would buy.
     *
     * <p>Never null. An empty list means this destination does not encrypt to keys, which is the
     * question {@link #encryptsToKey()} answers and the one place it is asked.
     */
    @Transient public java.util.List<BackupRecipient> recipients = java.util.List.of();

    public boolean encryptsToKey() {
        return recipients != null && !recipients.isEmpty();
    }

    /** The public halves alone, which is all the cipher needs. */
    public java.util.List<String> recipientKeys() {
        return recipients == null
                ? java.util.List.of()
                : recipients.stream().map(recipient -> recipient.publicKey).toList();
    }

    public boolean encrypts() {
        return encryptsToKey() || (encryptionPassphrase != null && !encryptionPassphrase.isBlank());
    }

    /**
     * The jump host this destination is reached through, if any.
     *
     * <p>An SFTP drop and a MinIO are as likely to be behind a jump host as the servers are, and
     * before tunnels were rows there was no way to say so — the tunnel that reached the targets
     * existed and could not be pointed at anything else.
     */
    @Column(name = "tunnel_id")
    public Long tunnelId;

    // Retention is not here. It belongs to the job, because it applies to the files that job
    // writes: two schedules pointed at the same bucket with different prefixes — a nightly
    // keeping seven and a weekly keeping four — would otherwise have to agree on one number,
    // and whichever ran last would delete the other's history.
}
