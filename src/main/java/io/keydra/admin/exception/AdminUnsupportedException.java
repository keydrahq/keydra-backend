package io.keydra.admin.exception;

/** Raised when a target's engine cannot be reconfigured while it runs. */
public class AdminUnsupportedException extends RuntimeException {

    public AdminUnsupportedException(String engine) {
        super("The " + engine + " engine cannot be reconfigured while it runs");
    }
}
