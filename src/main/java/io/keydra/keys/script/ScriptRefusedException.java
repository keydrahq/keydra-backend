package io.keydra.keys.script;

/**
 * A script that will not be run: it does not compile, or it did something at run time that stops
 * the migration.
 *
 * <p>Its own exception rather than a generic failure because it is the one kind of migration
 * failure that is the caller's to fix, and the message is the interpreter's own — a line number and
 * what it did not understand, which is what somebody editing a script needs.
 */
public class ScriptRefusedException extends RuntimeException {

    public ScriptRefusedException(String message, Throwable cause) {
        super(message, cause);
    }

    public ScriptRefusedException(String message) {
        super(message);
    }
}
