package io.keydra.authz.dto;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One of somebody's sessions, as they see it.
 *
 * @param id what ending it names; the same value the browser holds in a cookie
 * @param current whether this is the session reading the page — ending it is signing out, and a
 *     list that did not say which one is which invites somebody to do that by accident
 * @param userAgent the browser as it described itself, which is a label rather than evidence
 * @param network the address with its last part removed: enough to say "that is not where I work"
 */
@Schema(name = "Session", description = "A browser that is signed in")
public record SessionSummary(
        String id,
        boolean current,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        String userAgent,
        String network) {}
