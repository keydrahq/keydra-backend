package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * A store's own command language, for stores that have one.
 *
 * <p>Kept off {@link KeyValueEngine} and handed out through {@link KeyValueEngine#console()}
 * instead, because not every backing store has a command line to offer. A key-value store reached
 * only through a typed client has no console, and saying so with an empty Optional is honest in a
 * way that an inherited method throwing UnsupportedOperationException is not.
 */
public interface CommandConsole {

    /**
     * Runs one already-parsed command.
     *
     * @param argv the command name followed by its arguments, exactly as typed
     * @return the reply, including a {@link ConsoleValue.Failure} when the store rejected it
     */
    Uni<ConsoleValue> execute(ConnectionProfile profile, List<String> argv);
}
