package io.keydra.keys.dto;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

/**
 * The name a key is written under, which is the only part of a migration that changes a key.
 *
 * <p>Everything else about moving a keyspace preserves what it moves. Renaming does not, and it is
 * the one option here that can be asked for wrongly in a way the store will happily carry out — so
 * what it does with each shape of input is written down rather than left to be discovered on
 * somebody's keyspace.
 */
class MigrateKeysRequestTest {

    private static MigrateKeysRequest rewriting(String strip, String add) {
        // The two trailing nulls are the names phase 59 asks for where either end of a move is
        // guarded. Neither end is, in a test about rewriting names.
        return new MigrateKeysRequest(
                2L, null, "*", null, strip, add, null, null, true, false, null, null, null);
    }

    @Test
    void leavesNamesAloneWhenNeitherPrefixIsGiven() {
        MigrateKeysRequest request = rewriting(null, null);

        assertThat(request.rewritesNames(), is(false));
        assertThat(request.destinationName("user:1:profile"), equalTo("user:1:profile"));
    }

    @Test
    void stripsAPrefixAndPutsAnotherOn() {
        MigrateKeysRequest request = rewriting("staging:", "prod:");

        assertThat(request.rewritesNames(), is(true));
        assertThat(request.destinationName("staging:user:1"), equalTo("prod:user:1"));
    }

    /** Either half on its own, because a migration usually wants one or the other. */
    @Test
    void doesEitherHalfAlone() {
        assertThat(
                rewriting("staging:", null).destinationName("staging:user:1"), equalTo("user:1"));
        assertThat(rewriting(null, "prod:").destinationName("user:1"), equalTo("prod:user:1"));
    }

    /**
     * A name that does not carry the prefix is written unchanged rather than skipped or cut.
     *
     * <p>The caller asked for a prefix to be removed where it is there. A walk selected by one glob
     * still turns up names that do not all share a prefix, and the alternatives are both worse:
     * cutting blindly renames {@code user:1} to {@code r:1}, and skipping silently leaves keys
     * behind in a job that reported itself finished.
     */
    @Test
    void leavesANameThatDoesNotCarryThePrefix() {
        MigrateKeysRequest request = rewriting("staging:", null);

        assertThat(request.destinationName("user:1"), equalTo("user:1"));
    }

    /** An empty string is not a prefix, which is what an untouched form field sends. */
    @Test
    void treatsEmptyPrefixesAsNoneAtAll() {
        MigrateKeysRequest request = rewriting("", "");

        assertThat(request.rewritesNames(), is(false));
        assertThat(request.destinationName("user:1"), equalTo("user:1"));
    }
}
