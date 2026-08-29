package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;

/**
 * One of the codes that gets somebody back in without their phone.
 *
 * <p>Without these a second factor is a way to lose an account. A lost phone is the ordinary case,
 * not the exception, and an administrator who has to remove somebody's factor for them is a support
 * channel that also happens to be a way around the factor.
 *
 * <p>Hashed with SHA-256 rather than Argon2, which is the opposite of what a password gets and is
 * right for the same reason: Argon2 is slow because a password is guessable, and a recovery code is
 * a hundred and twenty bits this machine chose. There is nothing to slow an attacker down about —
 * they would be guessing the whole keyspace — and ten Argon2 comparisons on every sign-in attempt
 * would be a way to spend the server's memory.
 */
@Entity
@Table(
        name = "user_recovery_code",
        indexes = {@Index(name = "idx_recovery_code_user", columnList = "user_id")})
@IdClass(RecoveryCode.Key.class)
public class RecoveryCode {

    @Id
    @Column(name = "user_id", nullable = false)
    public Long userId;

    /** The code's SHA-256, hex. The code itself is shown once and never stored. */
    @Id
    @Column(name = "code_hash", nullable = false, length = 64)
    public String codeHash;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /** When it was spent. A code works once, and a used one stays as a record that it was. */
    @Column(name = "used_at")
    public Instant usedAt;

    /** The pair that identifies a row. */
    public static class Key implements Serializable {
        public Long userId;
        public String codeHash;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && java.util.Objects.equals(userId, key.userId)
                    && java.util.Objects.equals(codeHash, key.codeHash);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, codeHash);
        }
    }
}
