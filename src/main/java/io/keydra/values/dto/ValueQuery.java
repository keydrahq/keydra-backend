package io.keydra.values.dto;

/**
 * Which slice of a value to read.
 *
 * <p>Engine-neutral and the same shape for every type, because the alternative — a query record per
 * type — pushes the type switch into every layer that passes one along.
 *
 * @param key the key to read
 * @param cursor position to resume from: a SCAN cursor for hash, set and sorted-set, an index for
 *     list, an entry id for stream, ignored for string
 * @param count how many elements to return
 */
public record ValueQuery(String key, String cursor, int count) {

    public static final String CURSOR_START = "0";
    public static final int DEFAULT_COUNT = 200;

    public static ValueQuery first(String key) {
        return new ValueQuery(key, CURSOR_START, DEFAULT_COUNT);
    }
}
