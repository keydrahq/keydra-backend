package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.CommandStream;
import io.keydra.engine.ObservedCommand;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MONITOR, which is how a RESP store shows what it is being asked to do.
 *
 * <p>A watcher gets a connection of its own, taken outside the pool, for the same reason a
 * subscription does: MONITOR puts a connection into a mode where it answers nothing else, so a
 * pooled one used for it would stop serving the key browser that shares it.
 *
 * <p>The store reports one line per command, formatted for a person:
 *
 * <pre>1339518083.107412 [0 127.0.0.1:60866] "SET" "key" "value"</pre>
 *
 * <p>Parsed here rather than passed on, because everything worth doing with it upstream — filtering
 * by command, by client, by database — needs the parts rather than the sentence.
 */
@ApplicationScoped
public class RespCommandStream implements CommandStream {

    /**
     * Commands whose arguments are removed before they leave this class.
     *
     * <p>MONITOR shows everything, which includes the commands that carry credentials. Redis
     * redacts AUTH itself on recent versions and older ones do not, and Keydra has no business
     * relaying a password either way — so the arguments are dropped here regardless of what the
     * server chose to send.
     */
    private static final Set<String> SECRET =
            Set.of("AUTH", "HELLO", "CONFIG", "MIGRATE", "RESTORE");

    /** What a redacted argument is replaced with, rather than being left out entirely. */
    private static final String REDACTED = "(redacted)";

    private final RespConnectionPool pool;

    @Inject
    RespCommandStream(RespConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Multi<ObservedCommand> observe(ConnectionProfile profile) {
        return Multi.createFrom()
                .emitter(
                        emitter ->
                                // The client may need a tunnel opened first, so it is asked for
                                // asynchronously and the watch starts once it exists.
                                pool.createUnpooled(profile)
                                        .subscribe()
                                        .with(
                                                client -> observeWith(client, emitter),
                                                emitter::fail));
    }

    /** Wires one client's frames into the emitter and starts the watch. */
    private static void observeWith(Redis client, MultiEmitter<? super ObservedCommand> emitter) {
        client.connect()
                .onFailure(emitter::fail)
                .onSuccess(
                        connection -> {
                            connection.handler(frame -> emit(emitter, frame));
                            connection.exceptionHandler(emitter::fail);
                            // A dropped connection ends the stream rather than leaving a
                            // watcher that will never be told anything again.
                            connection.endHandler(ignored -> emitter.complete());
                            // Closing the connection is what stops the watch: there is no
                            // command that leaves monitor mode.
                            emitter.onTermination(client::close);
                            connection.send(Request.cmd(Command.MONITOR)).onFailure(emitter::fail);
                        });
    }

    private static void emit(MultiEmitter<? super ObservedCommand> emitter, Response frame) {
        // The first frame is MONITOR's own OK, and anything unparseable is not a command
        // this can report honestly, so both are simply not emitted.
        ObservedCommand command = parse(frame == null ? null : frame.toString());
        if (command != null) {
            emitter.emit(command);
        }
    }

    /**
     * Reads one MONITOR line.
     *
     * <p>Returns null for anything that is not one, which includes the OK that acknowledges the
     * command itself. Arguments are quoted and may contain escaped quotes, so they are read a
     * character at a time rather than split on spaces.
     */
    static ObservedCommand parse(String line) {
        if (line == null || line.isBlank() || !line.contains("[")) {
            return null;
        }

        int bracket = line.indexOf('[');
        int close = line.indexOf(']', bracket);
        if (close < 0) {
            return null;
        }

        long atMicros = timestamp(line.substring(0, bracket).trim());
        String origin = line.substring(bracket + 1, close);
        // "[0 127.0.0.1:60866]" for a client, "[0]" for a command the store issued itself.
        int space = origin.indexOf(' ');
        int database = parseInt(space < 0 ? origin : origin.substring(0, space));
        String client = space < 0 ? null : origin.substring(space + 1).trim();

        List<String> quoted = quotedParts(line.substring(close + 1));
        if (quoted.isEmpty()) {
            return null;
        }

        String name = quoted.get(0).toUpperCase();
        List<String> arguments = quoted.subList(1, quoted.size());
        return new ObservedCommand(
                atMicros,
                database,
                client,
                name,
                SECRET.contains(name) ? arguments.stream().map(a -> REDACTED).toList() : arguments);
    }

    /** The store reports seconds with a fractional part; microseconds is what orders them. */
    private static long timestamp(String seconds) {
        try {
            return Math.round(Double.parseDouble(seconds) * 1_000_000);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** Every quoted run in the rest of the line, with escapes resolved. */
    private static List<String> quotedParts(String rest) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = null;
        boolean escaped = false;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (current == null) {
                if (c == '"') {
                    current = new StringBuilder();
                }
                continue;
            }
            if (escaped) {
                current.append(unescape(c));
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                parts.add(current.toString());
                current = null;
            } else {
                current.append(c);
            }
        }
        return parts;
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> c;
        };
    }
}
