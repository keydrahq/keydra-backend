package io.keydra.engine.tikv;

import org.tikv.shade.com.google.protobuf.ByteString;

/**
 * Turning a glob into the one thing TiKV can be asked for: a range.
 *
 * <p>TiKV has no pattern matching. What it has is "give me the keys from here up to there", which
 * is a better fit for this application than it sounds — a glob's literal beginning *is* a range,
 * and most globs people write have one. {@code user:*} becomes everything from {@code user:} up to
 * the next key that cannot start with it, and the server never sees the rest of the keyspace.
 *
 * <p>Where a glob has no literal beginning — {@code *:profile} — there is no range and the walk is
 * the whole keyspace with the pattern applied as the keys come past. That is honest rather than
 * clever: the alternative is pretending a store has an index it does not have.
 */
final class TikvRanges {

    private TikvRanges() {}

    /** Everything up to the first character a pattern could match differently. */
    static ByteString startOf(String glob) {
        return ByteString.copyFromUtf8(literalPrefix(glob));
    }

    /**
     * The first key past the prefix, which is where the scan stops.
     *
     * <p>Made by adding one to the last byte rather than by appending anything: a prefix's range
     * ends at the next value of its final byte, and {@code user:} therefore ends at {@code user;}.
     * Appending a high byte instead would miss any key that happened to carry a higher one.
     */
    static ByteString endOf(String glob) {
        byte[] prefix = literalPrefix(glob).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (prefix.length == 0) {
            // No prefix at all: the range is the whole keyspace, which TiKV spells as an empty
            // end key rather than as a very large one.
            return ByteString.EMPTY;
        }
        byte[] end = java.util.Arrays.copyOf(prefix, prefix.length);
        for (int i = end.length - 1; i >= 0; i--) {
            if ((end[i] & 0xFF) != 0xFF) {
                end[i]++;
                return ByteString.copyFrom(end, 0, i + 1);
            }
        }
        // Every byte was 0xFF, so there is nothing after this prefix: scan to the end.
        return ByteString.EMPTY;
    }

    /** How much of a glob is a literal, which is how much of it a range can express. */
    static String literalPrefix(String glob) {
        if (glob == null || glob.isBlank()) {
            return "";
        }
        int end = 0;
        while (end < glob.length()) {
            char c = glob.charAt(end);
            if (c == '*' || c == '?' || c == '[' || c == '\\') {
                break;
            }
            end++;
        }
        return glob.substring(0, end);
    }

    /** Whether a name matches the glob, applied here because TiKV applies nothing. */
    static boolean matches(String name, String glob) {
        if (glob == null || glob.isBlank() || "*".equals(glob)) {
            return true;
        }
        StringBuilder pattern = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> pattern.append(".*");
                case '?' -> pattern.append('.');
                default -> pattern.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return name.matches(pattern.toString());
    }
}
