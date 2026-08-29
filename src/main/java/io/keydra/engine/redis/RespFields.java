package io.keydra.engine.redis;

import io.vertx.redis.client.Response;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a flat field-and-value array into a map.
 *
 * <p>RESP2 has no map type, so a command answering with named fields sends alternating names and
 * values. Several commands do this — {@code SENTINEL MASTERS}, {@code CONFIG GET} — and each of
 * them pairing the array up itself is how one of them ends up doing it wrong.
 */
final class RespFields {

    private RespFields() {}

    static Map<String, String> pairs(Response entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (entry == null) {
            return fields;
        }
        for (int i = 0; i + 1 < entry.size(); i += 2) {
            Response name = entry.get(i);
            Response value = entry.get(i + 1);
            if (name != null) {
                fields.put(name.toString(), value == null ? null : value.toString());
            }
        }
        return fields;
    }

    /** A field as an integer, or null when it is absent or not one. */
    static Integer integer(String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
