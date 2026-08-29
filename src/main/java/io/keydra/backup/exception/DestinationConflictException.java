package io.keydra.backup.exception;

/**
 * An edit a destination cannot accept.
 *
 * <p>Distinct from {@link BackupFailedException}, which is the place itself refusing or being
 * unreachable. This one never leaves Keydra: a name already taken, a destination that is not there,
 * a kind missing the field it cannot work without.
 */
public class DestinationConflictException extends RuntimeException {

    public DestinationConflictException(String message) {
        super(message);
    }
}
