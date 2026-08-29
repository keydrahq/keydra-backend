package io.keydra.authz.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The authenticator somebody has paired with their account.
 *
 * <p>A table of its own rather than columns on {@link AppUser}, because a second factor has a
 * lifecycle the account does not: it is begun, then confirmed, and it can be removed without the
 * account going anywhere. Keeping it apart also means the row that holds a password hash and the
 * row that holds a TOTP secret are two rows — which is worth something on the day one of them is
 * read by somebody who should not have.
 */
@Entity
@Table(name = "user_second_factor")
public class SecondFactor {

    /** One per account, so the account's own id is the key. */
    @Id
    @Column(name = "user_id")
    public Long userId;

    /**
     * The shared secret, base32 as an authenticator expects it.
     *
     * <p>Encrypted at rest with the same key everything else sensitive uses. It cannot be hashed —
     * verifying a code means computing one, which means having the secret back — so this is the one
     * credential in Keydra that is reversible by design, and docs/DATA-AT-REST.md says so.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 512)
    public String secret;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /**
     * When the pairing was proved, or null while it is only begun.
     *
     * <p>Nothing enforces a second factor until this is set, and that is the point: somebody who
     * scans nothing and closes the page has not locked themselves out. A row with no confirmation
     * is an attempt, and the next attempt replaces it.
     */
    @Column(name = "confirmed_at")
    public Instant confirmedAt;

    public boolean isConfirmed() {
        return confirmedAt != null;
    }
}
