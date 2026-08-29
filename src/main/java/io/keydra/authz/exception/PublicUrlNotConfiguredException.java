package io.keydra.authz.exception;

/**
 * Raised where Keydra needs to say where a browser reaches it and nobody has told it.
 *
 * <p>The address could be worked out from the request, and outside development it deliberately is
 * not. The {@code Host} header is written by whoever sent the request, so a redirect URI or a
 * post-sign-in destination built from it is a destination the sender chose. Refusing is the only
 * answer that cannot be steered.
 *
 * <p>Only the operations that publish an address raise this — agreeing a redirect URI with an
 * identity provider, and sending somebody back after one. An instance that signs everybody in
 * against local accounts never needs a public URL and is never asked for one.
 */
public class PublicUrlNotConfiguredException extends RuntimeException {

    public PublicUrlNotConfiguredException() {
        super(
                "This instance does not know its own address. Set KEYDRA_PUBLIC_URL to the address"
                        + " people reach Keydra at, then try again.");
    }
}
