package io.keydra.keys.exception;

/** The key does not exist on the target, or expired between being listed and being opened. */
public class KeyNotFoundException extends RuntimeException {

    public KeyNotFoundException(String key) {
        super("No key named '" + key + "' on this target");
    }
}
