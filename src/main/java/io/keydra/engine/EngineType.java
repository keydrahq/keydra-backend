package io.keydra.engine;

/**
 * Backing store a connection profile talks to.
 *
 * <p>The constant names a <em>protocol</em>, not a product. Redis and Valkey share one entry
 * because they speak the same protocol; they are told apart at runtime by capability detection, not
 * by configuration. The same is true of the other RESP-speaking stores Keydra plans to support —
 * KeyDB, DragonflyDB and Garnet — which need flavour detection (ROADMAP Phase 6), not a second
 * engine.
 *
 * <p>A store with a genuinely different protocol gets its own constant and its own {@link
 * KeyValueEngine} implementation. Aerospike and TiKV are the planned examples: neither speaks RESP,
 * and neither maps onto SCAN cursors or per-key TTL the way this one does.
 */
public enum EngineType {
    /** Anything speaking RESP: Redis, Valkey, and the compatible forks. */
    RESP,

    /**
     * Aerospike, which speaks its own protocol and keeps a different shape of key.
     *
     * <p>A record is identified by a namespace, a set and a user key rather than by one string, and
     * — this is the part that decides what Keydra can offer — the user key is only stored when the
     * application that wrote it asked for that. The default is not to: the server keeps a digest
     * and nothing else, so for most existing data there is no name to show. What works is browsing
     * by set, reading and writing a record's bins, and its expiry; what does not is anything that
     * needs a name it was never given.
     */
    AEROSPIKE,

    /**
     * TiKV, which is a flat keyspace of bytes and very little else.
     *
     * <p>Its keys map onto this application's better than Aerospike's do — they are strings of
     * bytes, so the namespace tree and prefix globs work without translation, and a glob's literal
     * prefix becomes the range scan TiKV actually offers. What it has none of is everything above
     * that: no types, no server statistics, no command language, no pub/sub.
     *
     * <p>And one thing worth knowing before reading the engine. Asking a TiKV for a key's
     * time-to-live, on a cluster that was not started with TTL enabled, does not answer an error:
     * the server panics and the process dies. So this engine never asks. The TTL column is empty
     * for a TiKV target and that is a deliberate refusal rather than a gap.
     */
    TIKV
}
