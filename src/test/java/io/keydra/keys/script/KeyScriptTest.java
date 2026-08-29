package io.keydra.keys.script;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** What a migration script can say about a key, and what happens when it says something else. */
class KeyScriptTest {

    private static final long NO_EXPIRY = -1;

    @Test
    void movesAKeyUnchangedWhenTheScriptSaysNothingAboutIt() {
        KeyDecision decision = KeyScript.compile("return true").decide("user:1", NO_EXPIRY);

        assertThat(decision.move(), is(true));
        assertThat(decision.name(), equalTo("user:1"));
        assertThat(decision.ttlMillis(), equalTo(NO_EXPIRY));
    }

    @Test
    void renamesWhatTheScriptRenames() {
        KeyScript script =
                KeyScript.compile("return { name = key.name:gsub('^staging:', 'prod:') }");

        assertThat(script.decide("staging:user:1", NO_EXPIRY).name(), equalTo("prod:user:1"));
    }

    /** The case options cannot cover: a rule that is a rule rather than a prefix. */
    @Test
    void skipsWhatTheScriptSkips() {
        KeyScript script =
                KeyScript.compile("if key.name:find('^tmp:') then return nil end\nreturn true");

        assertThat(script.decide("tmp:working", NO_EXPIRY).move(), is(false));
        assertThat(script.decide("user:1", NO_EXPIRY).move(), is(true));
    }

    /** Returning false is the other way of saying no, and people write both. */
    @Test
    void readsFalseAsNoAsWell() {
        assertThat(KeyScript.compile("return false").decide("user:1", NO_EXPIRY).move(), is(false));
    }

    @Test
    void setsTheExpiryWhenTheScriptSetsIt() {
        KeyScript script = KeyScript.compile("return { ttlMillis = 604800000 }");

        assertThat(script.decide("user:1", NO_EXPIRY).ttlMillis(), equalTo(604_800_000L));
    }

    /** A table that names neither leaves both alone rather than blanking them. */
    @Test
    void changesOnlyWhatTheTableNames() {
        KeyDecision decision = KeyScript.compile("return {}").decide("user:1", 5_000);

        assertThat(decision.name(), equalTo("user:1"));
        assertThat(decision.ttlMillis(), equalTo(5_000L));
    }

    /**
     * A script keeps what it puts in its own globals, for the length of one migration.
     *
     * <p>Useful — numbering keys, counting what was skipped — and safe, because the sandbox belongs
     * to the job. Another job compiling the same source gets its own.
     */
    @Test
    void remembersBetweenKeysWithinOneMigration() {
        KeyScript script =
                KeyScript.compile("seen = (seen or 0) + 1\nreturn { name = 'k:' .. seen }");

        assertThat(script.decide("a", NO_EXPIRY).name(), equalTo("k:1"));
        assertThat(script.decide("b", NO_EXPIRY).name(), equalTo("k:2"));
        assertThat(KeyScript.compile("return type(seen)").decide("c", NO_EXPIRY).move(), is(true));
    }

    @Test
    void refusesSomethingThatIsNotLua() {
        ScriptRefusedException refused =
                assertThrows(
                        ScriptRefusedException.class, () -> KeyScript.compile("this is not lua"));

        // The interpreter's own words, which name a line and what it did not understand.
        assertThat(refused.getMessage(), containsString("migration"));
    }

    /** A script that fails on one key stops the migration rather than quietly moving it anyway. */
    @Test
    void refusesWhenTheScriptFailsOnAKey() {
        KeyScript script = KeyScript.compile("return key.name.nothing.here");

        assertThrows(ScriptRefusedException.class, () -> script.decide("user:1", NO_EXPIRY));
    }

    /** And the sandbox's limit reaches this path too, not only a bare chunk. */
    @Test
    @Timeout(10)
    void stopsAScriptThatNeverReturns() {
        KeyScript script = KeyScript.compile("while true do end");

        ScriptRefusedException stopped =
                assertThrows(
                        ScriptRefusedException.class, () -> script.decide("user:1", NO_EXPIRY));
        assertThat(stopped.getMessage(), containsString("ran too long"));
    }

    /** Every door is still shut when the script is reached this way rather than directly. */
    @Test
    void hasNoWayOutHereEither() {
        KeyScript script = KeyScript.compile("return { name = type(luajava) .. '/' .. type(os) }");

        assertThat(script.decide("user:1", NO_EXPIRY).name(), equalTo("nil/nil"));
    }
}
