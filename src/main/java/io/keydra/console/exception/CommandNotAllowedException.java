package io.keydra.console.exception;

/** The command is refused by policy rather than by the server. */
public class CommandNotAllowedException extends RuntimeException {

    private final String command;

    public CommandNotAllowedException(String command, String reason) {
        super(reason);
        this.command = command;
    }

    public String command() {
        return command;
    }
}
