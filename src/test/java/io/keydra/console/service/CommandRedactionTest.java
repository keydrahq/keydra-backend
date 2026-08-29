package io.keydra.console.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The secrets that must not reach the console history.
 *
 * <p>A history holds whatever anybody typed, and a password typed into a console would otherwise be
 * a password in the database in the clear — readable by anyone who can read a backup of it, and
 * outliving the rotation it was part of.
 */
class CommandRedactionTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "AUTH hunter2                                  | AUTH ******",
                "auth alice hunter2                            | auth ****** ******",
                "CONFIG SET requirepass hunter2                 | CONFIG SET requirepass ******",
                "config set masterauth hunter2                  | config set masterauth ******",
                "ACL SETUSER alice on >hunter2 ~key:* +get      | ACL SETUSER alice on >******"
                        + " ~key:* +get",
                "ACL SETUSER alice on #abc123 +get              | ACL SETUSER alice on #******"
                        + " +get",
            })
    void takesTheSecretOutAndLeavesTheRest(String typed, String expected) {
        assertThat(redact(typed), equalTo(expected));
    }

    @Test
    void keepsTheHalfOfConfigSetThatIsNotASecret() {
        // CONFIG SET takes pairs since Redis 7, and a history entry that masked the whole line
        // would lose the change somebody is trying to recall.
        assertThat(
                redact("CONFIG SET maxmemory 2gb requirepass hunter2"),
                equalTo("CONFIG SET maxmemory 2gb requirepass ******"));
    }

    @Test
    void keepsWhereAMigrationWasGoing() {
        // The destination is the part worth remembering; only the credential is taken out.
        assertThat(
                redact("MIGRATE 10.0.0.4 6379 session:1 0 5000 AUTH hunter2"),
                equalTo("MIGRATE 10.0.0.4 6379 session:1 0 5000 AUTH ******"));
    }

    @Test
    void seesThroughQuoting() {
        // Works on the parsed arguments, so quotes cannot hide a secret from it.
        String redacted = redact("CONFIG SET \"requirepass\" 'hunter2'");

        assertThat(redacted, not(org.hamcrest.Matchers.containsString("hunter2")));
    }

    @ParameterizedTest
    @CsvSource({
        "GET session:1",
        "CONFIG SET maxmemory 2gb",
        "ACL SETUSER alice on ~key:* +get",
        "ACL LIST",
        "'INFO memory'",
    })
    void leavesAnOrdinaryCommandAlone(String typed) {
        assertThat(redact(typed), equalTo(typed));
    }

    @Test
    void cannotSeeASecretThatIsJustAValue() {
        // Stated as a test because it is the honest limit: no policy can know that this
        // argument is a token, which is why the history is trimmed rather than kept for ever.
        assertThat(redact("SET session:token s3cr3t"), equalTo("SET session:token s3cr3t"));
    }

    private static String redact(String typed) {
        return CommandRedaction.of(typed, new CommandParser().parse(typed));
    }
}
