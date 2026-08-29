package io.keydra.keys.script;

/**
 * What a script decided about one key.
 *
 * @param move whether the key is moved at all
 * @param name the name to write it under, which is its own name unless the script changed it
 * @param ttlMillis what is left of its life, or -1 for a key that does not expire
 */
public record KeyDecision(boolean move, String name, long ttlMillis) {

    /** The answer for a key no script was asked about. */
    public static KeyDecision keep(String name, long ttlMillis) {
        return new KeyDecision(true, name, ttlMillis);
    }
}
