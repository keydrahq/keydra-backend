package io.keydra.common.graphql;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A position in a list, written down so it can be handed back.
 *
 * <p>What a cursor holds is the value the list is ordered by and the id of the row it belongs to —
 * enough to say "carry on from after this one" in a WHERE clause. That is what makes paging stable:
 * a row added while somebody is reading page one does not shift page two, because page two is not
 * "rows 21 to 40", it is "the rows after that one". Keydra's audit log takes a row on every action,
 * so with offsets a page turned during ordinary use showed a row that was already on the page
 * before it.
 *
 * <p>The ordering is written into the cursor as well, and checked when it comes back. A cursor
 * taken from a list sorted by name means nothing in a list sorted by date — it would resume from a
 * position that does not exist there, and the answer would be quietly wrong rather than refused.
 *
 * <p>Base64 for the same reason as {@link GlobalId}: not secrecy, but a sign that there is nothing
 * inside to depend on. The format may change; a cursor from an earlier release is refused rather
 * than misread, which is what the version marker is for.
 */
public final class Cursors {

    private static final String VERSION = "v1";

    /**
     * A newline, because the parts are a version, an ordering, a sort value and an id — and the
     * sort value is a server name somebody typed. Any printable separator is a character somebody
     * can put in a name; a newline is not.
     */
    private static final String SEPARATOR = "\n";

    private Cursors() {}

    /** Writes down a position within a particular ordering. */
    public static String of(String ordering, String sortValue, Object id) {
        String joined =
                String.join(
                        SEPARATOR,
                        VERSION,
                        ordering,
                        sortValue == null ? "" : sortValue,
                        String.valueOf(id));
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor back, or null when it is not usable here.
     *
     * <p>Null covers all the ways a cursor can be wrong at once — not base64, an older format, or
     * taken from a differently ordered list — because they all mean the same thing to a caller:
     * this cannot be resumed from.
     */
    public static Position read(String cursor, String ordering) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(SEPARATOR, -1);
            if (parts.length != 4 || !VERSION.equals(parts[0]) || !ordering.equals(parts[1])) {
                return null;
            }
            return new Position(parts[2].isEmpty() ? null : parts[2], parts[3]);
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }

    /** The value the list was ordered by, and the id of the row that held it. */
    public record Position(String sortValue, String id) {}
}
