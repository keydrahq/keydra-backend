package io.keydra.schedule.entity;

import io.keydra.authz.entity.Permission;

/**
 * The kinds of work that can be arranged to happen later.
 *
 * <p>Each names the permission somebody would need to do it by hand, because that is exactly what
 * scheduling it requires: a schedule is a way of doing something later, not a way of doing
 * something you may not do. The check happens when a schedule is saved and again when it runs — an
 * arrangement made by somebody who has since lost the access should stop, not carry on.
 *
 * <p>Deliberately few, and all three the same shape: the things people already write cron entries
 * and shell scripts for. What that costs them is a schedule living somewhere Keydra cannot see, a
 * second copy of the credentials, and no way of finding out it stopped running until whatever it
 * fed is empty.
 */
public enum JobType {

    /**
     * Empties a database.
     *
     * <p>The one everybody has a script for, and the one everybody has run against the wrong target
     * at least once — which is why arranging it is a permission of its own and why the dialog asks
     * for the target by name.
     */
    FLUSH_DATABASE(Permission.KEYS_DELETE),

    /**
     * Copies keys to another target.
     *
     * <p>The migration Keydra already does, on a cadence, through the same service — so a nightly
     * copy and a manual one are the same code and fail the same way.
     */
    COPY_KEYS(Permission.MIGRATION_RUN),

    /** Writes keys out to a file, which is a backup that needs no second server. */
    EXPORT_KEYS(Permission.TRANSFER_EXPORT);

    private final Permission required;

    JobType(Permission required) {
        this.required = required;
    }

    /** What somebody must hold on the target to arrange this, and to have it keep running. */
    public Permission required() {
        return required;
    }
}
