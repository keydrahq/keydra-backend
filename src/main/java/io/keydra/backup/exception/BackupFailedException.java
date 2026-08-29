package io.keydra.backup.exception;

/**
 * A destination that would not take the backup, or would not give it back.
 *
 * <p>One exception for all four kinds, carrying what the library said. The reason a backup failed
 * is nearly always the same handful — wrong credentials, no such bucket, a directory that is not
 * writable — and which library reported it is not the part anybody needs.
 */
public class BackupFailedException extends RuntimeException {

    public BackupFailedException(String message) {
        super(message);
    }

    public BackupFailedException(String message, Throwable cause) {
        super(message + ": " + rootMessage(cause), cause);
    }

    /**
     * The innermost message, because the outer ones are usually wrappers.
     *
     * <p>"Unable to execute HTTP request" over "Connection refused" tells somebody nothing they can
     * act on; the sentence underneath it names the actual problem.
     */
    private static String rootMessage(Throwable cause) {
        Throwable deepest = cause;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
    }
}
