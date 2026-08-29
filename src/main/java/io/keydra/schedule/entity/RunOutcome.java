package io.keydra.schedule.entity;

/** How one attempt ended. */
public enum RunOutcome {

    /**
     * Still going.
     *
     * <p>Written before the work starts rather than after it ends. A job that never finishes has to
     * be visible as one that never finished; recorded only on completion, it would be
     * indistinguishable from one that never ran.
     */
    RUNNING,

    DONE,

    FAILED,

    /**
     * Not attempted, because whoever arranged it no longer holds the permission.
     *
     * <p>Its own outcome rather than a failure: nothing went wrong, and the fix is a grant rather
     * than an investigation.
     */
    REFUSED,

    /**
     * The instance running it went away.
     *
     * <p>Written by whichever instance takes on the chores next, because the one that was running
     * the job has nobody left to record anything. Its own outcome rather than a failure for the
     * same reason a migration has one: nothing refused anything and nothing went wrong with the
     * work — the process simply stopped, and what it had already done is done.
     *
     * <p>The run is not started again here. A schedule says when its job should run, and the honest
     * thing to do about a nightly flush that was interrupted at three is to run it at three
     * tomorrow rather than at whatever time the handover happened to notice.
     */
    INTERRUPTED
}
