package io.keydra.schedule.job;

import io.keydra.authz.entity.Permission;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.ScheduledJob;
import io.smallrye.mutiny.Uni;

/**
 * One kind of work that can be arranged to happen later.
 *
 * <p>An interface with an implementation each, the way engines are: adding a job type is adding a
 * class, not editing the scheduler. The scheduler knows how to decide something is due, how to
 * record an attempt and how to refuse one; it knows nothing about what any of them do.
 */
public interface JobHandler {

    /** Which job this handles. Exactly one handler per type, checked at startup. */
    JobType handles();

    /**
     * Does the work.
     *
     * @return one line saying what it did, which is what a run history shows
     */
    Uni<String> run(ScheduledJob job);

    /**
     * Whether these settings make sense, before the schedule is saved.
     *
     * <p>At configuration time rather than at three in the morning: a schedule with a target that
     * does not exist should be refused by the person making it, not discovered by whoever reads the
     * failures a week later.
     *
     * @throws io.keydra.schedule.exception.ScheduleRefusedException when they do not
     */
    default void check(ScheduledJob job) {}

    /**
     * What these particular settings ask for, beyond the permission the job type always needs.
     *
     * <p>Empty for almost every job, because what a job needs is normally a property of its type
     * and {@link JobType#required()} already says it. The exception is a setting that turns the job
     * into something else as well: a copy carrying a script runs code inside Keydra as well as
     * moving keys, and running code is a different permission from moving them.
     *
     * <p>Read at every firing rather than once when the schedule was written — that is the whole
     * point, and the reasoning is in {@code RunGuard}. A handler reading settings here must answer
     * "nothing" for settings it cannot read: the run that follows refuses them anyway, so claiming
     * no permission is needed is safe only because nothing then happens.
     */
    default java.util.Set<Permission> alsoNeeds(ScheduledJob job) {
        return java.util.Set.of();
    }
}
