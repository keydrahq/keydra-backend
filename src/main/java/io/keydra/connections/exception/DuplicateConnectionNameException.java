package io.keydra.connections.exception;

/** Another connection profile already uses the requested name. Mapped to 409. */
public class DuplicateConnectionNameException extends RuntimeException {

    public DuplicateConnectionNameException(String name) {
        super("A connection profile named '" + name + "' already exists");
    }
}
