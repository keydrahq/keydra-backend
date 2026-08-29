package io.keydra.authz.exception;

/**
 * A sign-in through an external provider that did not work.
 *
 * <p>The message is written to be read by whoever was trying to sign in and by the administrator
 * who has to fix it, which are usually different people with different questions. It never carries
 * anything the provider sent back beyond a description of what went wrong: a failed token exchange
 * often echoes the request, and the request has a client secret in it.
 */
public class SignInFailedException extends RuntimeException {

    public SignInFailedException(String message) {
        super(message);
    }

    public SignInFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
