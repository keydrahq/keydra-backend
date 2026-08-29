package io.keydra.connections.exception;

/**
 * The profile was refused because of what it says, not because of what happened.
 *
 * <p>Every check that runs before a profile is saved throws this: certificates that do not parse, a
 * client certificate with no key, a console allowance naming a command that cannot be allowed. All
 * of them exist for one reason — the moment to find out is while somebody is looking at the form —
 * and until this existed they were {@code IllegalArgumentException}, which nothing mapped, so the
 * sentence written for a person to read arrived as a 500 with a stack trace behind it.
 *
 * <p>Its own type rather than mapping {@code IllegalArgumentException}: that one is also what a bug
 * throws, and turning every bug in this domain into a 400 carrying its internal message would be
 * telling a caller their request was wrong when it was not.
 */
public class InvalidConnectionException extends RuntimeException {

    public InvalidConnectionException(String message) {
        super(message);
    }
}
