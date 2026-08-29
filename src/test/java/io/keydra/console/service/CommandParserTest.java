package io.keydra.console.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.keydra.console.exception.MalformedCommandException;
import org.junit.jupiter.api.Test;

class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @Test
    void splitsOnWhitespace() {
        assertThat(parser.parse("GET user:1"), contains("GET", "user:1"));
    }

    @Test
    void collapsesRunsOfWhitespace() {
        assertThat(parser.parse("  SET   a    b  "), contains("SET", "a", "b"));
    }

    @Test
    void keepsQuotedArgumentsWhole() {
        assertThat(
                parser.parse("SET greeting \"hello world\""),
                contains("SET", "greeting", "hello world"));
        assertThat(
                parser.parse("SET greeting 'hello world'"),
                contains("SET", "greeting", "hello world"));
    }

    @Test
    void treatsAnEmptyQuotedStringAsAnArgument() {
        // SET k "" stores an empty string; dropping the argument would change the command.
        assertThat(parser.parse("SET k \"\""), contains("SET", "k", ""));
    }

    @Test
    void unescapesInsideDoubleQuotesOnly() {
        assertThat(parser.parse("SET k \"a\\nb\""), contains("SET", "k", "a\nb"));
        // Single quotes are literal, the same way a shell treats them.
        assertThat(parser.parse("SET k 'a\\nb'"), contains("SET", "k", "a\\nb"));
    }

    @Test
    void keepsAQuoteThatWasEscaped() {
        assertThat(parser.parse("SET k \"say \\\"hi\\\"\""), contains("SET", "k", "say \"hi\""));
    }

    @Test
    void refusesAnUnbalancedQuoteRatherThanGuessing() {
        // Closing it silently would run a command the user did not write.
        assertThrows(MalformedCommandException.class, () -> parser.parse("SET k \"unfinished"));
    }

    @Test
    void returnsNothingForABlankLine() {
        assertThat(parser.parse("   "), empty());
    }
}
