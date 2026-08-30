package io.keydra.engine.tikv;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;
import org.tikv.shade.com.google.protobuf.ByteString;

/**
 * The one translation a TiKV engine lives or dies by.
 *
 * <p>TiKV has no pattern matching, only "the keys from here up to there". So a glob is worth
 * exactly as much as the literal it starts with: `user:*` is a range the server can serve, and
 * `*:profile` is a walk of the whole keyspace with the pattern applied as keys go past. Getting the
 * end of a range wrong is not a slow query, it is a wrong answer — keys silently missing from a
 * list somebody is reading to decide something.
 */
class TikvRangesTest {

    private static String text(ByteString bytes) {
        return bytes.toStringUtf8();
    }

    @Test
    void takesTheLiteralBeginningOfAGlob() {
        assertThat(TikvRanges.literalPrefix("user:*"), equalTo("user:"));
        assertThat(TikvRanges.literalPrefix("user:1?"), equalTo("user:1"));
        assertThat(TikvRanges.literalPrefix("plain"), equalTo("plain"));
    }

    /** A glob that begins with a pattern has no range, and says so rather than guessing one. */
    @Test
    void hasNoRangeForAGlobThatStartsWithAPattern() {
        assertThat(TikvRanges.literalPrefix("*:profile"), equalTo(""));
        assertThat(text(TikvRanges.startOf("*")), equalTo(""));
        // TiKV spells "to the end of the keyspace" as an empty key rather than a very large one.
        assertThat(TikvRanges.endOf("*"), equalTo(ByteString.EMPTY));
    }

    /**
     * A prefix ends at the next value of its last byte.
     *
     * <p>`user:` therefore ends at `user;`, because `;` is `:` plus one. The obvious alternative —
     * appending a very high byte — misses any key that happens to carry a higher one, and that is
     * the shape of bug where a list looks complete and is not.
     */
    @Test
    void endsARangeAtTheNextKeyAfterThePrefix() {
        assertThat(text(TikvRanges.startOf("user:*")), equalTo("user:"));
        assertThat(text(TikvRanges.endOf("user:*")), equalTo("user;"));
    }

    /**
     * A prefix outside ASCII still ends one past its last byte, in the encoding it is sent in.
     *
     * <p>Worth pinning down, because the glob arrives as a string and TiKV addresses bytes. `ÿ` is
     * one character and two bytes of UTF-8 — C3 BF — so the range after it ends at C3 C0, and has
     * nothing to do with the character's own value. Reading the character rather than the bytes is
     * how a range comes out wrong for every key that is not ASCII.
     *
     * <p>The engine also guards the case where every byte is already the highest one. No string can
     * produce that through this path, because UTF-8 never emits FF; the guard is a line, and its
     * absence would be an overflow.
     */
    @Test
    void endsARangeInTheEncodingItIsSentIn() {
        assertThat(
                TikvRanges.startOf("ÿ").toByteArray(),
                equalTo(new byte[] {(byte) 0xC3, (byte) 0xBF}));
        assertThat(
                TikvRanges.endOf("ÿ").toByteArray(),
                equalTo(new byte[] {(byte) 0xC3, (byte) 0xC0}));
    }

    /** The pattern itself is applied here, because the server applies nothing. */
    @Test
    void appliesTheGlobToWhatComesBack() {
        assertThat(TikvRanges.matches("user:1", "user:*"), is(true));
        assertThat(TikvRanges.matches("cache:1", "user:*"), is(false));
        assertThat(TikvRanges.matches("user:1", "user:?"), is(true));
        assertThat(TikvRanges.matches("user:12", "user:?"), is(false));
        assertThat(TikvRanges.matches("anything", "*"), is(true));
        assertThat(TikvRanges.matches("anything", null), is(true));
    }
}
