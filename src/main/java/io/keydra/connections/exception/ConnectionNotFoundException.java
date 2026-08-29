package io.keydra.connections.exception;

/** No connection profile exists with the requested id. Mapped to 404. */
public class ConnectionNotFoundException extends RuntimeException {

    public ConnectionNotFoundException(Long id) {
        super("No connection profile with id " + id);
    }
}
