package io.keydra.approvals.exception;

/**
 * Something about a request could not be done: it is not yours to withdraw, it has already been
 * answered, or you are the person who asked for it.
 *
 * <p>One type for all of them because they share an answer — the caller cannot do this and the
 * sentence says which of those it is — and because the alternative is a type per sentence.
 */
public class ApprovalRefusedException extends RuntimeException {

    public ApprovalRefusedException(String message) {
        super(message);
    }
}
