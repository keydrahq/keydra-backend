package io.keydra.console.service;

import io.keydra.console.exception.MalformedCommandException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a typed line into a command and its arguments.
 *
 * <p>Follows redis-cli's rules rather than inventing new ones, because the line being typed is
 * almost always one copied from documentation or from a colleague's terminal: whitespace separates
 * arguments, single and double quotes group them, and a backslash escapes the next character inside
 * double quotes.
 *
 * <p>An unterminated quote is an error, not a guess. Silently closing it would run a command the
 * user did not write.
 */
@ApplicationScoped
public class CommandParser {

    public List<String> parse(String line) {
        List<String> argv = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        char quote = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (quote != 0) {
                if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    current.append(unescape(line.charAt(++i)));
                } else if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                // An empty quoted string is still an argument, so opening a quote commits to one.
                quote = c;
                inArgument = true;
            } else if (Character.isWhitespace(c)) {
                if (inArgument) {
                    argv.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
            } else {
                current.append(c);
                inArgument = true;
            }
        }

        if (quote != 0) {
            throw new MalformedCommandException("Unbalanced " + quote + " quote");
        }
        if (inArgument) {
            argv.add(current.toString());
        }
        return argv;
    }

    /** Escapes redis-cli understands inside double quotes; anything else is itself. */
    private static char unescape(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> escaped;
        };
    }
}
