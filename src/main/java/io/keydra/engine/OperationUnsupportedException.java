package io.keydra.engine;

/**
 * Raised when a store has no such operation at all.
 *
 * <p>Not a failure of the attempt — the attempt was never possible. TiKV has no rename and no copy;
 * Aerospike has neither either; TiKV is never asked about expiry, because on a cluster started
 * without it the question stops the server. The interface is told all three through {@link
 * Capabilities}, so in the ordinary course nobody ever reaches this: the menu item is not there.
 * What this is for is everything else — a target that could not be asked what it supports, a
 * request made straight to the API, a page open since before somebody changed the server.
 *
 * <p>It exists so those arrive as a refusal with a sentence in it rather than as a 500 with a stack
 * trace, which is the difference between "this store cannot do that" and "Keydra broke".
 */
public class OperationUnsupportedException extends RuntimeException {

    public OperationUnsupportedException(String message) {
        super(message);
    }
}
