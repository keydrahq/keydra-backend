package io.keydra.engine.redis;

import io.keydra.engine.ClusterHealth;
import java.util.Map;

/**
 * Reads what {@code CLUSTER INFO} answers.
 *
 * <p>Flat {@code key:value} lines with no section headers, which is the same shape {@code INFO}
 * uses inside a section — so the same parser reads it and everything lands under the name it gives
 * fields that were never introduced by a header.
 *
 * <p>Every number is read with a fallback rather than required. A fork that answers this command
 * with fewer fields than Redis does should produce a partial picture, not an exception on a page
 * whose whole job is to describe an arrangement somebody is worried about.
 */
final class RespClusterHealth {

    /** What the section with no header is called by {@link RespInfo}. */
    private static final String UNSECTIONED = "general";

    private RespClusterHealth() {}

    /** The cluster's verdict, or null when it would not give one. */
    static ClusterHealth parse(String info) {
        if (info == null || info.isBlank()) {
            return null;
        }
        Map<String, String> fields = RespInfo.parse(info).get(UNSECTIONED);
        if (fields == null || !fields.containsKey("cluster_state")) {
            return null;
        }
        return new ClusterHealth(
                fields.getOrDefault("cluster_state", "unknown").trim(),
                number(fields, "cluster_slots_assigned"),
                number(fields, "cluster_slots_ok"),
                number(fields, "cluster_slots_pfail"),
                number(fields, "cluster_slots_fail"),
                number(fields, "cluster_known_nodes"),
                number(fields, "cluster_size"),
                longNumber(fields, "cluster_current_epoch"));
    }

    private static int number(Map<String, String> fields, String name) {
        return (int) longNumber(fields, name);
    }

    private static long longNumber(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
