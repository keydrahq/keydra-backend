package io.keydra.alerts.exception;

/**
 * A rule or a delivery that cannot work as written.
 *
 * <p>Refused while somebody is looking at it rather than at four in the morning, which is the
 * argument this codebase makes everywhere it validates: the same mistake caught later is a line in
 * a log nobody reads until the alert that should have fired did not.
 */
public class AlertRefusedException extends RuntimeException {

    public AlertRefusedException(String message) {
        super(message);
    }
}
