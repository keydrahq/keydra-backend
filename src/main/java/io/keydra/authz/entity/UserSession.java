package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One signed-in browser.
 *
 * <p>Until this existed a session was a cookie and nothing else: encrypted, signed and expiring,
 * but with no record on the server that it had been issued. Which meant there was no list of who
 * was signed in, no way to end one session without ending them all, and — the part that matters — a
 * cookie somebody took was good until it expired. Changing a password did not stop it, because
 * there was nothing to stop.
 *
 * <p>What is stored is the least that answers "is this one of mine, and is it still good": when it
 * started, when it was last used, when it lapses, and enough about where it came from for somebody
 * to recognise their own laptop in a list — and to not recognise the one that is not theirs.
 */
@Entity
@Table(
        name = "user_session",
        indexes = {
            @Index(name = "idx_user_session_user", columnList = "user_id"),
            @Index(name = "idx_user_session_expires", columnList = "expires_at")
        })
public class UserSession {

    /**
     * The id, which is what the cookie carries.
     *
     * <p>A random UUID rather than a sequence: it travels to the browser, and a number that
     * increments tells anybody holding one roughly how many people use this Keydra and lets them
     * guess at their neighbours'.
     */
    @Id
    @Column(length = 36)
    public String id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    @Column(name = "issued_at", nullable = false)
    public Instant issuedAt = Instant.now();

    /**
     * When it was last used, written on a slow clock.
     *
     * <p>Every request with a cookie could update this, and doing so would put a write in front of
     * every call to record something nobody reads to the minute. The same throttle the account's
     * own "last seen" uses.
     */
    @Column(name = "last_seen_at")
    public Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    /** Set when somebody ended it. Never cleared: a session that came back would be a new one. */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    /**
     * The browser it was issued to, as the browser described itself.
     *
     * <p>Truncated, and stored for one purpose: so a person reading their own list can tell one row
     * from another. It is not evidence of anything — a user agent is whatever the client says.
     */
    @Column(name = "user_agent", length = 400)
    public String userAgent;

    /**
     * The network it came from, not the address.
     *
     * <p>The first three parts of an IPv4 address, or the first four groups of an IPv6 one. Enough
     * to say "this is not where I work" and not enough to be a log of somebody's movements — which
     * is what a full address in a table nobody prunes becomes.
     */
    @Column(name = "network", length = 64)
    public String network;

    /** Whether this session can still be presented, which is the only question anything asks. */
    public boolean isLive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
