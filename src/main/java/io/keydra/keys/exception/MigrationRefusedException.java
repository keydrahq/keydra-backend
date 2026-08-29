package io.keydra.keys.exception;

/**
 * Raised when a migration cannot start: no target, or the same target on both ends.
 *
 * <p>Refused before the job exists, so the caller gets an answer to the request it made rather than
 * a job that appears in the list and immediately fails.
 */
public class MigrationRefusedException extends RuntimeException {

    public MigrationRefusedException(String message) {
        super(message);
    }
}
