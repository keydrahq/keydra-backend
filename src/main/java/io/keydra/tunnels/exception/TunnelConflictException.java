package io.keydra.tunnels.exception;

/**
 * An edit a tunnel cannot accept.
 *
 * <p>Distinct from {@link TunnelException}, which is the jump host itself refusing or being
 * unreachable. This one never leaves Keydra: a name already taken, a tunnel that is not there, a
 * tunnel still being used by something.
 */
public class TunnelConflictException extends RuntimeException {

    public TunnelConflictException(String message) {
        super(message);
    }
}
