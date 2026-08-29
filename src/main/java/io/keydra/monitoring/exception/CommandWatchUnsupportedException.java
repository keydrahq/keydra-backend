package io.keydra.monitoring.exception;

/** Raised when a target's engine cannot show what it is being asked to do. */
public class CommandWatchUnsupportedException extends RuntimeException {

    public CommandWatchUnsupportedException(String engine) {
        super("The " + engine + " engine cannot stream the commands a target is running");
    }
}
