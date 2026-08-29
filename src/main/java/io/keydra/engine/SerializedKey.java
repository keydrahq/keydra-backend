package io.keydra.engine;

/**
 * One key as bytes, exactly as the store serialises it.
 *
 * <p>The payload is opaque on purpose. A type-aware export — read the value, write JSON — has to
 * decide what a stream's entry ids mean, what to do with a hash field that is not valid UTF-8, and
 * how to page a list of a million entries; every one of those decisions loses something. The
 * store's own serialisation loses nothing, and restoring it is one command.
 *
 * <p>The price is that the format belongs to the store: a dump taken from one is only guaranteed to
 * restore into another that speaks the same protocol and is no older. That is why {@link
 * KeyTransfer} is an optional capability rather than a method every engine must answer, and why the
 * export file records which engine wrote it.
 *
 * @param key the key's name
 * @param ttlMillis remaining life in milliseconds, or 0 for a key that does not expire
 * @param payload the store's serialisation of the value
 */
public record SerializedKey(String key, long ttlMillis, byte[] payload) {

    /** A key with no expiry, which is what a store reports as "no TTL" once normalised. */
    public static final long NO_EXPIRY = 0L;
}
