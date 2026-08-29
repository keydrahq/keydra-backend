package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.CommandConsole;
import io.keydra.engine.ConsoleValue;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.ResponseType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a typed command line against a RESP server.
 *
 * <p>An error reply comes back as {@link ConsoleValue.Failure} rather than as a thrown exception: a
 * console exists to show what the server said, and {@code WRONGTYPE} is an answer. Only a broken
 * connection is a failure of the request itself.
 */
@ApplicationScoped
public class RespCommandConsole implements CommandConsole {

    private final RespConnectionPool pool;

    @Inject
    RespCommandConsole(RespConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Uni<ConsoleValue> execute(ConnectionProfile profile, List<String> argv) {
        if (argv.isEmpty()) {
            return Uni.createFrom().item(new ConsoleValue.Nil());
        }

        Request request = Request.cmd(Command.create(argv.get(0).toLowerCase(Locale.ROOT)));
        argv.subList(1, argv.size()).forEach(request::arg);

        return pool.send(profile, request)
                .map(RespCommandConsole::toValue)
                .onFailure()
                .recoverWithItem(
                        error ->
                                // The server refusing a command arrives here as an exception
                                // carrying its message; that message is the answer.
                                new ConsoleValue.Failure(
                                        error.getMessage() == null
                                                ? error.getClass().getSimpleName()
                                                : error.getMessage()));
    }

    /** Translates one RESP reply, recursively for the nested ones. */
    static ConsoleValue toValue(Response response) {
        if (response == null) {
            return new ConsoleValue.Nil();
        }
        ResponseType type = response.type();
        return switch (type) {
            case SIMPLE, BULK -> new ConsoleValue.Text(response.toString());
            case ERROR -> new ConsoleValue.Failure(response.toString());
            case BOOLEAN -> new ConsoleValue.Bool(Boolean.TRUE.equals(response.toBoolean()));
            case NUMBER -> number(response);
            // ATTRIBUTE is RESP3 metadata attached to a reply and is shaped like a map,
            // so it renders as one rather than being dropped.
            case MULTI, PUSH, ATTRIBUTE -> nested(response);
        };
    }

    /**
     * RESP3 folds integers and doubles into one type, so the value decides.
     *
     * <p>Rendering a count as {@code 3.0} or a score as {@code 3} would both be wrong, and only
     * looking at the number tells them apart.
     */
    private static ConsoleValue number(Response response) {
        Double value = response.toDouble();
        if (value == null) {
            return new ConsoleValue.Nil();
        }
        return value % 1 == 0 && !value.isInfinite()
                ? new ConsoleValue.Number(response.toLong())
                : new ConsoleValue.Decimal(value);
    }

    /**
     * An array reply, nested exactly as the client presents it.
     *
     * <p>No attempt is made to decide which arrays were "really" maps. RESP2 has no map type, and
     * every way of guessing is wrong somewhere: the client's {@code getKeys()} pairs an even-length
     * array up as a map — which is right for {@code CONFIG GET} and wrong for a two-element {@code
     * LRANGE} — and throws outright on an odd-length one.
     *
     * <p>Guessing is also unnecessary. Where the client does model an association it nests the
     * pairs itself: {@code HGETALL} arrives as an array of two-element arrays, so the field names
     * are already there. Reporting the structure as given loses nothing and invents nothing.
     */
    private static ConsoleValue nested(Response response) {
        List<ConsoleValue> items = new ArrayList<>(response.size());
        response.forEach(item -> items.add(toValue(item)));
        return new ConsoleValue.Sequence(items);
    }
}
