package io.keydra.values.decoder;

/**
 * Renders raw bytes as something a person can read.
 *
 * <p>Decoding is done on the server so the browser never handles raw bytes, and so a compressed or
 * binary-packed value is legible without the UI shipping a decoder for every format anyone might
 * have stored.
 *
 * <p>Implementations are ordered by {@link #priority()} and the first that {@link #canDecode} wins,
 * so the chain reports the most specific format rather than falling back to bytes-as-text.
 */
public interface ValueDecoder {

    /** Identifier returned to the client, e.g. "json". */
    String id();

    /** Lower runs first. Specific formats must outrank the catch-all. */
    int priority();

    /** Whether these bytes look like this decoder's format. */
    boolean canDecode(byte[] raw);

    /** Renders the bytes. Only called when {@link #canDecode} returned true. */
    String decode(byte[] raw);
}
