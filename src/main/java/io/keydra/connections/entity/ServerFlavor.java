package io.keydra.connections.entity;

/**
 * Which server a profile is expected to be pointing at.
 *
 * <p>Distinct from {@link io.keydra.engine.EngineType}, which names a <em>protocol</em>: Redis and
 * Valkey both speak RESP, so they are one engine and two flavours. Which one a target actually runs
 * is still read from the server when it answers — this is what the profile says to expect, which is
 * what a target that has never been reached has instead.
 *
 * <p>A fork that answers something else is not wrong for doing so; it simply has no entry here yet,
 * and {@link #UNKNOWN} is what a profile says until somebody adds one.
 */
public enum ServerFlavor {
    UNKNOWN,
    REDIS,
    VALKEY,
    KEYDB,
    DRAGONFLY,
    GARNET,
    AEROSPIKE,
    TIKV;

    /** The name a server reports for itself, matched to an entry here. */
    public static ServerFlavor of(String reported) {
        if (reported == null) {
            return UNKNOWN;
        }
        return switch (reported.toLowerCase()) {
            case "redis" -> REDIS;
            case "valkey" -> VALKEY;
            case "keydb" -> KEYDB;
            case "dragonfly" -> DRAGONFLY;
            case "garnet" -> GARNET;
            case "aerospike" -> AEROSPIKE;
            case "tikv" -> TIKV;
            default -> UNKNOWN;
        };
    }
}
