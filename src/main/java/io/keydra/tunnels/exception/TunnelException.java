package io.keydra.tunnels.exception;

/** The tunnel could not be opened, so the target cannot be reached at all. */
public class TunnelException extends RuntimeException {

    public TunnelException(String message, Throwable cause) {
        super(message, cause);
    }

    public TunnelException(String message) {
        super(message);
    }
}
