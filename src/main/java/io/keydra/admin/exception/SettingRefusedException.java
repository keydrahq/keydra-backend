package io.keydra.admin.exception;

/**
 * Raised when the server would not take a setting's value.
 *
 * <p>A refusal is the caller's business rather than a fault in Keydra: an unknown setting name, a
 * value outside the range, a policy that does not exist. The server's own words are kept, because
 * they say which of those it was and nothing here could say it better.
 */
public class SettingRefusedException extends RuntimeException {

    public SettingRefusedException(String name, Throwable cause) {
        super("The server would not set " + name + ": " + cause.getMessage(), cause);
    }
}
