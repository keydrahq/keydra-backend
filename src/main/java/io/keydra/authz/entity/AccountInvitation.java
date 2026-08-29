package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A link that lets one person set one password, once, before a deadline.
 *
 * <p>What is stored is the hash of the token, never the token. The link is the credential — anybody
 * holding the string can take the account it names — so it is kept the way a password is, and a
 * database that leaked these would be a database that leaked nothing usable.
 *
 * <p>Both purposes share this row on purpose. An invitation and a password reset are the same
 * mechanism seen from two sides, and a second implementation would be a second place to get token
 * handling wrong — which is the part where getting it wrong hands somebody an account.
 */
@Entity
@Table(
        name = "account_invitation",
        indexes = {
            @Index(name = "idx_invitation_token", columnList = "token_hash", unique = true),
            @Index(name = "idx_invitation_user", columnList = "user_id")
        })
public class AccountInvitation {

    /** Why the link was sent, which is only ever a difference in wording. */
    public enum Purpose {
        /** A new account nobody has signed into yet. */
        INVITATION,
        /** An account whose owner cannot get in. */
        RESET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "account_invitation_seq")
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    /**
     * SHA-256 of the token, hex encoded.
     *
     * <p>Not Argon2, and the difference from a password is the point: a password is short, chosen
     * by a person and worth spending half a second to make guessing expensive. This is 256 bits
     * from a secure random source, which no amount of guessing reaches — so the hash only has to be
     * one-way, and it has to be fast because it is computed on the request that redeems the link.
     */
    @Column(name = "token_hash", nullable = false, length = 64)
    public String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    public Purpose purpose = Purpose.INVITATION;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /** Who sent it, as a name rather than an id so it still reads as itself after they leave. */
    @Column(name = "created_by", length = 200)
    public String createdBy;

    /** When it was used. Set once and never cleared: a link that works twice is two accounts. */
    @Column(name = "accepted_at")
    public Instant acceptedAt;

    /** Whether this link can still be redeemed, which is the only question anything asks it. */
    public boolean isLive(Instant now) {
        return acceptedAt == null && expiresAt.isAfter(now);
    }
}
