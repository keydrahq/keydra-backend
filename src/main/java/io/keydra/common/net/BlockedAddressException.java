package io.keydra.common.net;

/** Raised when Keydra is asked to make a request to an address it will not reach. */
public class BlockedAddressException extends RuntimeException {

    public BlockedAddressException(String message) {
        super(message);
    }
}
