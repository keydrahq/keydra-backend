package io.keydra.common.graphql;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * An id that means the same thing wherever it appears.
 *
 * <p>A type name and the key within that type, joined and base64-encoded. Base64 not for secrecy —
 * anybody can decode it in a second — but as a sign that there is nothing inside worth reading. An
 * id that reads as a number invites a client to do arithmetic on it, and then the day the key stops
 * being a database row is the day that client breaks.
 *
 * <p>The type is part of it so that two rows numbered 4 in two tables are not the same object, and
 * so that {@code node(id:)} knows what to go and fetch without being told.
 */
public final class GlobalId {

    private static final String SEPARATOR = ":";

    private GlobalId() {}

    /** The id a client sees, for one object of one type. */
    public static String of(String type, Object key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((type + SEPARATOR + key).getBytes(StandardCharsets.UTF_8));
    }

    /** What a client sent, taken apart, or null when it was not one of ours. */
    public static Parts read(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
            int at = decoded.indexOf(SEPARATOR);
            return at <= 0 ? null : new Parts(decoded.substring(0, at), decoded.substring(at + 1));
        } catch (IllegalArgumentException notOurs) {
            // Not base64, so not an id this server issued. A refusal, not a failure: the caller
            // decides whether an unknown id is an empty answer or an error.
            return null;
        }
    }

    /** The type an id belongs to, and the key inside it. */
    public record Parts(String type, String key) {}
}
