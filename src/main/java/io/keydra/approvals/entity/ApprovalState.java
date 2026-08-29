package io.keydra.approvals.entity;

/**
 * Where a request has got to.
 *
 * <p>One line, and it only ever moves forward. {@link #PENDING} is the only state anybody can act
 * on, which is what makes two people pressing approve at the same moment a question the database
 * answers rather than one the application has to.
 */
public enum ApprovalState {

    /** Written down, waiting for somebody who is not the person who asked. */
    PENDING,

    /** Somebody agreed and the work is under way. */
    RUNNING,

    /** It happened. */
    DONE,

    /**
     * It was agreed to and then failed.
     *
     * <p>Kept apart from {@link #DECLINED} because they are opposite news: one says nobody wanted
     * this, the other says everybody did and the server did not manage it.
     */
    FAILED,

    /** Somebody said no, and said why. */
    DECLINED,

    /** The person who asked changed their mind. */
    WITHDRAWN,

    /**
     * Nobody answered in time.
     *
     * <p>A distinct ending rather than a quiet deletion: the failure this prevents is somebody
     * believing an operation is arranged when it is never going to happen.
     */
    EXPIRED;

    /** Whether this is an ending, which is every state except the two that are still moving. */
    public boolean isFinal() {
        return this != PENDING && this != RUNNING;
    }
}
