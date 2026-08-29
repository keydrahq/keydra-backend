package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Changing how a server is configured and told to keep its data.
 *
 * <p>Optional on {@link KeyValueEngine} like the console and the message bus: a store whose
 * settings are not readable or changeable at runtime says so with an empty Optional rather than by
 * throwing at the first call.
 *
 * <p>Everything here changes the server rather than the data in it, which is a different kind of
 * act: a badly chosen maxmemory policy can lose keys that were never asked to expire, and a save
 * started at the wrong moment can stall a busy instance. So each is a deliberate call rather than
 * something a page does while rendering, and the endpoints above them are for operators only.
 */
public interface ServerAdmin {

    /**
     * The settings the server will report, and whether each can be changed while it runs.
     *
     * @param glob which settings to fetch; the store's own pattern syntax, "*" for all
     */
    Uni<List<ServerSetting>> settings(ConnectionProfile profile, String glob);

    /**
     * Changes one setting for as long as the server runs.
     *
     * <p>Not written to the server's configuration file: making a change permanent is a separate
     * act with a separate risk — a file rewritten badly is a server that will not start — and it is
     * {@link #persistSettings} that does it.
     */
    Uni<Void> changeSetting(ConnectionProfile profile, String name, String value);

    /** Writes the running configuration back to the file the server was started from. */
    Uni<Void> persistSettings(ConnectionProfile profile);

    /** How the server is keeping its data, and when it last managed to. */
    Uni<PersistenceState> persistence(ConnectionProfile profile);

    /**
     * Asks the server to write a snapshot in the background.
     *
     * <p>In the background because the foreground form blocks every client until it finishes, which
     * on a large instance is measured in seconds — long enough to time out whatever was using it.
     */
    Uni<Void> snapshot(ConnectionProfile profile);

    /** Asks the server to rewrite its append-only file, compacting it. */
    Uni<Void> rewriteLog(ConnectionProfile profile);
}
