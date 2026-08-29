package io.keydra.engine;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How much memory one key occupies.
 *
 * @param key the key
 * @param type what it holds
 * @param bytes memory it occupies, as the store measures it
 * @param elements how many elements it holds, or null for a type with no count
 * @param ttlMillis what is left of its life, or -1 when it has no expiry at all
 */
@Schema(name = "KeySize", description = "How much memory one key occupies")
public record KeySize(String key, String type, long bytes, Long elements, long ttlMillis) {

    /** What PTTL answers for a key that will never expire. */
    public static final long NO_EXPIRY = -1;
}
