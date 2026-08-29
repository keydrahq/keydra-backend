package io.keydra.engine.redis;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the text INFO returns.
 *
 * <p>The format is sections introduced by {@code # Name} and then {@code key:value} lines. Kept
 * apart from the engine because it is pure text handling with nothing reactive about it, which
 * makes it something a unit test can cover without a server.
 */
final class RespInfo {

    private RespInfo() {}

    /** Section name to its fields, in the order the server wrote them. */
    static Map<String, Map<String, String>> parse(String info) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (info == null) {
            return sections;
        }

        // Fields before any header belong to no section; the server does not emit any,
        // but a fork might, and dropping them silently would be worse than naming them.
        String current = "general";
        for (String rawLine : info.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                current = line.substring(1).trim().toLowerCase(java.util.Locale.ROOT);
                sections.computeIfAbsent(current, ignored -> new LinkedHashMap<>());
                continue;
            }
            int separator = line.indexOf(':');
            if (separator > 0) {
                sections.computeIfAbsent(current, ignored -> new LinkedHashMap<>())
                        .put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return sections;
    }

    /** A field from anywhere in the reply, since callers know field names, not sections. */
    static String field(Map<String, Map<String, String>> sections, String name) {
        for (Map<String, String> fields : sections.values()) {
            String value = fields.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** A field as a number, or null when it is absent or not one. */
    static Long number(Map<String, Map<String, String>> sections, String name) {
        String value = field(sections, name);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * How many keys the given database holds.
     *
     * <p>The keyspace section reports one line per database, shaped {@code
     * db0:keys=12,expires=3,avg_ttl=0}, so the count has to be picked out of it.
     */
    static Long keyCount(Map<String, Map<String, String>> sections, int database) {
        Map<String, String> keyspace = sections.get("keyspace");
        if (keyspace == null) {
            return null;
        }
        String line = keyspace.get("db" + database);
        if (line == null) {
            // A database with no keys is omitted entirely rather than reported as zero.
            return 0L;
        }
        for (String part : line.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && "keys".equals(pair[0].trim())) {
                try {
                    return Long.valueOf(pair[1].trim());
                } catch (NumberFormatException notANumber) {
                    return null;
                }
            }
        }
        return null;
    }
}
