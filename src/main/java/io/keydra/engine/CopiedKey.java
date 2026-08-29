package io.keydra.engine;

import java.util.List;

/**
 * A key as its values, for stores that will not accept each other's serialised form.
 *
 * <p>{@link SerializedKey} is the fast way to move a key: the store hands over its own bytes and
 * the other end takes them back without inspecting them. It only works between stores that agree on
 * that format, and two RESP forks often do not — Redis 8 stamps its dumps with an RDB version that
 * Valkey 9 refuses outright. This is the way that always works: read the value with ordinary
 * commands, write it with ordinary commands.
 *
 * <p>The writes are worked out where the value is read, because that is where the store's own shape
 * is known. Each entry is the arguments for one command, after the key's name; which command is
 * decided by {@link #type}. A string, a list, a set, a sorted set and a hash are one entry each; a
 * stream is one per entry, because a stream is written back one entry at a time.
 *
 * @param key the name
 * @param type the store's own word for the shape — "string", "list", "set", "zset", "hash",
 *     "stream"
 * @param ttlMillis remaining life in milliseconds, or {@link SerializedKey#NO_EXPIRY}
 * @param writes the arguments of each command that rebuilds the value
 */
public record CopiedKey(String key, String type, long ttlMillis, List<List<byte[]>> writes) {}
