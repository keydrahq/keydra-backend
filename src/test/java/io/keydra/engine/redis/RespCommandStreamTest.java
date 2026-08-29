package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.engine.ObservedCommand;
import org.junit.jupiter.api.Test;

/**
 * Reading MONITOR's own formatting.
 *
 * <p>Parsing is unit-tested rather than driven through a server because the shapes worth covering —
 * a quoted argument containing a space, an escaped quote, a command the store issued itself — are
 * awkward to provoke on a live target and trivial to state here.
 */
class RespCommandStreamTest {

    @Test
    void readsTheTimestampDatabaseClientAndArguments() {
        ObservedCommand command =
                RespCommandStream.parse(
                        "1339518083.107412 [0 127.0.0.1:60866] \"SET\" \"a\" \"1\"");

        assertThat(command.atMicros(), equalTo(1339518083107412L));
        assertThat(command.database(), equalTo(0));
        assertThat(command.client(), equalTo("127.0.0.1:60866"));
        assertThat(command.name(), equalTo("SET"));
        assertThat(command.arguments(), contains("a", "1"));
    }

    @Test
    void keepsAnArgumentThatContainsSpaces() {
        ObservedCommand command =
                RespCommandStream.parse("1.0 [0 127.0.0.1:1] \"SET\" \"greeting\" \"hello there\"");

        // Splitting on spaces would make this two arguments and the value wrong.
        assertThat(command.arguments(), contains("greeting", "hello there"));
    }

    @Test
    void keepsAnArgumentThatContainsAQuote() {
        ObservedCommand command =
                RespCommandStream.parse("1.0 [0 127.0.0.1:1] \"SET\" \"k\" \"say \\\"hi\\\"\"");

        assertThat(command.arguments(), contains("k", "say \"hi\""));
    }

    @Test
    void upperCasesTheCommandSoOneNameIsOneCommand() {
        // Clients send the name in whatever case they like, and a stream that reports both
        // cannot be filtered by command.
        assertThat(RespCommandStream.parse("1.0 [0 c:1] \"get\" \"k\"").name(), equalTo("GET"));
    }

    @Test
    void hasNoClientForACommandTheStoreIssuedItself() {
        ObservedCommand command = RespCommandStream.parse("1.0 [0] \"EXPIRED\" \"session:1\"");

        assertThat(command.client(), is(nullValue()));
        assertThat(command.database(), equalTo(0));
        assertThat(command.name(), equalTo("EXPIRED"));
    }

    @Test
    void removesTheArgumentsOfEveryCommandThatCarriesASecret() {
        // MONITOR shows everything, and older servers do not redact AUTH themselves. Keydra
        // has no business relaying a password either way.
        ObservedCommand auth = RespCommandStream.parse("1.0 [0 c:1] \"AUTH\" \"hunter2\"");

        assertThat(auth.name(), equalTo("AUTH"));
        assertThat(auth.arguments(), everyItem(equalTo("(redacted)")));
    }

    @Test
    void ignoresAnythingThatIsNotACommandLine() {
        // The first frame is MONITOR's own acknowledgement, and a half-line is not something
        // that can be reported honestly.
        assertThat(RespCommandStream.parse("OK"), is(nullValue()));
        assertThat(RespCommandStream.parse(""), is(nullValue()));
        assertThat(RespCommandStream.parse(null), is(nullValue()));
        assertThat(RespCommandStream.parse("1.0 [0 c:1]"), is(nullValue()));
    }
}
