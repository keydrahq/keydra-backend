package io.keydra.console.exception;

/** The line could not be split into a command — an unbalanced quote, for instance. */
public class MalformedCommandException extends RuntimeException {

    public MalformedCommandException(String message) {
        super(message);
    }
}
