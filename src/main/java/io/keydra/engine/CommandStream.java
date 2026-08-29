package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Multi;

/**
 * Everything a target is being asked to do, as it happens.
 *
 * <p>Optional on {@link KeyValueEngine} like the console and the message bus: a store that cannot
 * show its own traffic says so with an empty Optional rather than by throwing at the first call.
 *
 * <p>This is the most revealing thing Keydra can ask a server for — it is every command every
 * client sends, including the ones carrying data — so it is never started by accident: nothing
 * opens it but a request that says to, and it stops the moment the reader goes away.
 */
public interface CommandStream {

    /**
     * Streams commands until the subscriber cancels.
     *
     * <p>The stream is the observation: cancelling it stops watching and releases whatever the
     * store needed to hold it open. That matters here because observing puts a RESP connection into
     * a mode where it accepts nothing else, so the connection cannot be a shared one.
     */
    Multi<ObservedCommand> observe(ConnectionProfile profile);
}
