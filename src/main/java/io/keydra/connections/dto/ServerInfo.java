package io.keydra.connections.dto;

/**
 * What a target reported about itself.
 *
 * <p>Engine-neutral on purpose: each {@link io.keydra.engine.KeyValueEngine} decides how to obtain
 * these three facts from its own protocol, and nothing above the engine needs to know how.
 *
 * @param flavor product name, e.g. "redis" or "valkey"
 * @param version the server's own version string
 * @param mode topology it believes it is in, e.g. standalone or cluster
 */
public record ServerInfo(String flavor, String version, String mode) {

    public static final String FLAVOR_REDIS = "redis";
    public static final String FLAVOR_VALKEY = "valkey";

    /**
     * Forks that speak RESP and are told apart by the version field they add.
     *
     * <p>Each of these keeps {@code redis_version} for compatibility — that is the point of a fork
     * — and adds one of its own. Reading the compatibility field first would report every one of
     * them as an old Redis, which is how a UI ends up hiding features the server does have.
     */
    public static final String FLAVOR_KEYDB = "keydb";

    public static final String FLAVOR_DRAGONFLY = "dragonfly";
    public static final String FLAVOR_GARNET = "garnet";

    /**
     * Aerospike, which is not on the list above for a reason worth keeping visible.
     *
     * <p>Every other flavour here answers RESP and reports a {@code redis_version}; they differ in
     * what they add. This one differs in what it is: a different protocol, a different shape of
     * key, and a different set of things Keydra can offer for it.
     */
    public static final String FLAVOR_AEROSPIKE = "aerospike";

    /** TiKV, reached through its placement driver rather than directly. */
    public static final String FLAVOR_TIKV = "tikv";

    public static final String FLAVOR_UNKNOWN = "unknown";

    /**
     * Reported when the server does not say which mode it runs in.
     *
     * <p>Separate from {@link #FLAVOR_UNKNOWN} despite the same text: one describes the product,
     * the other its topology, and they are free to diverge.
     */
    public static final String MODE_UNKNOWN = "unknown";
}
