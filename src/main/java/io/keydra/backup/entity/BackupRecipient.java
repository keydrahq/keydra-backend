package io.keydra.backup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One key a destination's backups can be opened with.
 *
 * <p>A list rather than a column, because one recipient means the person holding that private half
 * is the only person in the world who can read a year of backups — and the fix everybody reaches
 * for first, sharing the private half, is worse than the problem: a secret two people hold is a
 * secret with no owner, and it cannot be taken back from one of them.
 *
 * <p>Adding one here does not make yesterday's backups readable by it. A file carries the
 * recipients it was written to, and nothing rewrites a file in a bucket.
 */
@Entity
@Table(name = "backup_recipient")
public class BackupRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "destination_id", nullable = false)
    public Long destinationId;

    /**
     * What somebody calls this key.
     *
     * <p>Required, and it is the field that makes the list usable: removing a recipient means
     * knowing which one, and the only other thing distinguishing them is forty characters of
     * base64.
     */
    @Column(nullable = false, length = 200)
    public String label;

    /**
     * The public half, which is not a secret.
     *
     * <p>Deliberately not stored like one: it is the half that only encrypts, and returning it is
     * how somebody checks the right key is configured. The half that decrypts was never here, which
     * is the whole claim of this mode.
     */
    @Column(name = "public_key", nullable = false, length = 200)
    public String publicKey;

    @Column(name = "added_at", nullable = false)
    public Instant addedAt = Instant.now();
}
