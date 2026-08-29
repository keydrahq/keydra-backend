package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

/**
 * Somebody who signs in.
 *
 * <p>A row exists for every identity Keydra has seen, whether it came from a provider or was
 * created here. A user arriving from a provider for the first time is created and holds nothing: a
 * stranger who has proved who they are is still a stranger.
 *
 * <p>The identity and what they may do are deliberately separate. This says who; {@link Grant} says
 * what, and nothing here carries a role.
 */
@Entity
@Table(
        name = "app_user",
        indexes = {
            @Index(name = "idx_app_user_username", columnList = "username", unique = true),
            @Index(name = "idx_app_user_external", columnList = "provider,external_id")
        })
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_seq")
    public Long id;

    /** What they sign in as. Unique across providers, so two directories cannot collide. */
    @Column(nullable = false, length = 200)
    @NotBlank
    public String username;

    @Column(name = "display_name", length = 200)
    public String displayName;

    @Column(length = 320)
    public String email;

    /**
     * Which provider vouches for them.
     *
     * <p>A name rather than an enum: providers are rows a deployment adds, and the set of them is
     * not knowable at compile time. "local" is the one this application is always able to offer.
     */
    @Column(nullable = false, length = 64)
    @NotBlank
    public String provider = "local";

    /** The provider's own identifier for them, which survives a rename on their side. */
    @Column(name = "external_id", length = 320)
    public String externalId;

    /**
     * Argon2id hash, for a local account.
     *
     * <p>Null for anyone a provider vouches for: Keydra has no business holding a password it did
     * not issue, and an empty one would be a password that matches nothing rather than nothing.
     */
    @Column(name = "password_hash", length = 256)
    public String passwordHash;

    /**
     * Whether they may sign in at all.
     *
     * <p>Separate from having no grants: somebody who has left should be stopped at the door rather
     * than admitted to an application that shows them nothing.
     */
    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "last_seen_at")
    public Instant lastSeenAt;
}
