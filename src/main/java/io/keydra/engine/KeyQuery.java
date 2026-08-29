package io.keydra.engine;

/**
 * Engine-neutral description of a key listing.
 *
 * @param match glob-style filter, or null for everything
 * @param count how much work the engine should do per step — a hint, not a result limit
 * @param type restrict to one value type, or null for all
 */
public record KeyQuery(String match, int count, String type) {

    public static final int DEFAULT_COUNT = 500;

    public static KeyQuery all() {
        return new KeyQuery(null, DEFAULT_COUNT, null);
    }

    public KeyQuery withMatch(String newMatch) {
        return new KeyQuery(newMatch, count, type);
    }
}
