package io.keydra.approvals.entity;

import io.keydra.authz.entity.Permission;

/**
 * The kinds of operation that can be made to wait for a second person.
 *
 * <p>Exactly the set phase 59 makes name its target, and deliberately not a set of its own: two
 * lists of dangerous operations are two lists that can disagree, and the one that is wrong is the
 * one nobody reads. What they have in common is a blast radius of a keyspace rather than of a key —
 * a single write, one key and a TTL are not here, because a confirmation asked on every ordinary
 * edit is one nobody reads by the third time.
 *
 * <p>Each names the permission whoever approves it must hold, which is the permission the operation
 * itself needs. That is the whole rule about who may approve: somebody who could have done it
 * alone, and who is not the person who asked.
 */
public enum ApprovalKind {

    /** Delete everything a glob matches. */
    PURGE_KEYS(Permission.KEYS_DELETE),

    /** Delete a named selection, which can be a keyspace by another name. */
    DELETE_KEYS(Permission.KEYS_DELETE),

    /** Write keys from a dump over whatever is there. */
    IMPORT_KEYS(Permission.TRANSFER_IMPORT),

    /**
     * Move or copy keys between two targets.
     *
     * <p>The only kind with two ends, and both of them count: writing into a target can overwrite
     * it, and moving keys off one empties it as surely as deleting them does.
     */
    MIGRATE_KEYS(Permission.MIGRATION_RUN),

    /**
     * Arrange one of the above to happen later.
     *
     * <p>Here because without it the phase has a hole wide enough to walk through: somebody who may
     * not purge a target alone would write a schedule that purges it in two minutes and be asked
     * nothing. What is approved is the arrangement, and approving it is what creates it — the
     * firing is not approved again, because nobody is present at three in the morning.
     *
     * <p>Needs what the work needs as well, which the handler adds: {@code schedule:manage} is a
     * way of doing something later, not a way of doing something you may not do.
     */
    SCHEDULE_WRITE(Permission.SCHEDULE_MANAGE);

    private final Permission required;

    ApprovalKind(Permission required) {
        this.required = required;
    }

    /** What somebody must hold to ask for this, and therefore to approve it. */
    public Permission required() {
        return required;
    }
}
