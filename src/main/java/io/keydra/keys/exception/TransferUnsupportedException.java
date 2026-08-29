package io.keydra.keys.exception;

/**
 * Raised when a target's store cannot serialise a key, so there is nothing to export or import.
 *
 * <p>A refusal rather than an empty result: an export that quietly produces nothing looks like a
 * store with no keys in it.
 */
public class TransferUnsupportedException extends RuntimeException {

    public TransferUnsupportedException(String engine) {
        super("The " + engine + " engine cannot export or import keys");
    }
}
