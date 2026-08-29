package io.keydra.keys.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One key as seen by the browser.
 *
 * @param ttl seconds until expiry, {@code -1} when the key has none and {@code -2} when it no
 *     longer exists — Redis' own convention, kept rather than mapped so nothing is lost
 */
@Schema(name = "KeyEntry", description = "A key with its type and time to live")
public record KeyEntry(String key, String type, long ttl) {

    public static final long NO_EXPIRY = -1;
    public static final long MISSING = -2;

    public boolean exists() {
        return ttl != MISSING;
    }
}
