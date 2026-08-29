package io.keydra.events.dto;

import java.time.Instant;
import java.util.Objects;

/**
 * Envelope broadcast to notification-hub subscribers.
 *
 * <p>Serialised as {@code { "category": string, "payload": object, "connectionId": number|null,
 * "ts": iso8601 }}.
 *
 * <p>The connection is what decides who gets it, and it is on the envelope rather than dug out of
 * the payload on the way past. Digging would work today — nearly every payload happens to carry a
 * {@code connectionId} — and would fail silently the first time somebody wrote one that did not. A
 * field that has to be filled in is a question the author of a new broadcast is made to answer.
 *
 * @param connectionId the target this concerns, or null when it concerns Keydra rather than any one
 *     target. Null means everybody who may be signed in sees it, so it is the answer for news that
 *     names nothing — and the wrong answer for anything carrying a key, a channel or a reading.
 */
public record Notification(String category, Object payload, Long connectionId, Instant ts) {

    public Notification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(ts, "ts");
    }

    /** For news about Keydra itself, which everybody signed in may see. */
    public static Notification of(String category, Object payload) {
        return new Notification(category, payload, null, Instant.now());
    }

    /** For news about one target, which only those who may see that target receive. */
    public static Notification about(String category, Long connectionId, Object payload) {
        return new Notification(category, payload, connectionId, Instant.now());
    }
}
