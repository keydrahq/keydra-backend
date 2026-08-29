package io.keydra.connections.dto;

import java.time.Instant;

/**
 * Point-in-time health of one profile.
 *
 * @param message failure reason when {@code state} is DOWN, otherwise null
 * @param server capabilities detected on the last successful check, otherwise null
 */
public record ConnectionStatus(
        ConnectionState state, String message, ServerInfo server, Instant checkedAt) {

    public static ConnectionStatus unknown() {
        return new ConnectionStatus(ConnectionState.UNKNOWN, null, null, null);
    }

    /**
     * A check is in flight.
     *
     * <p>Carries whatever was detected previously so a row already showing "redis 8.10.0" does not
     * blank out every time it is re-checked.
     */
    public static ConnectionStatus connecting(ServerInfo lastKnown) {
        return new ConnectionStatus(ConnectionState.CONNECTING, null, lastKnown, null);
    }

    public static ConnectionStatus up(ServerInfo server) {
        return new ConnectionStatus(ConnectionState.UP, null, server, Instant.now());
    }

    /**
     * The target did not answer.
     *
     * <p>Keeps the last known server details: "this was redis 8.10.0 and is now unreachable" is
     * more useful than forgetting what it ever was.
     */
    public static ConnectionStatus down(String message, ServerInfo lastKnown) {
        return new ConnectionStatus(ConnectionState.DOWN, message, lastKnown, Instant.now());
    }

    /** Status changes are what the hub broadcasts; timestamps alone must not trigger a message. */
    public boolean differsFrom(ConnectionStatus other) {
        if (other == null) {
            return true;
        }
        return state != other.state
                || !java.util.Objects.equals(message, other.message)
                || !java.util.Objects.equals(server, other.server);
    }
}
