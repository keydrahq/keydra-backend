package io.keydra.keys.dto;

import java.util.List;

/**
 * What to export: either an explicit list of keys, or everything matching a pattern.
 *
 * <p>Both, because the two are genuinely different jobs. A list is what the browser has when
 * someone ticks rows; a pattern is what a backup wants, and enumerating a million key names in a
 * request body to ask for them is not a reasonable way to say "all of them".
 *
 * @param keys the keys to export; when empty the pattern is used instead
 * @param match a glob the export walks the keyspace for, null for everything
 * @param limit how many keys the walk may take, so an export cannot run unbounded
 */
public record ExportKeysRequest(List<String> keys, String match, Integer limit) {

    /** Enough for a working set; a bigger export is a job for the store's own tools. */
    public static final int DEFAULT_LIMIT = 100_000;

    public int limitOrDefault() {
        return limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
    }
}
