package io.keydra.schedule.exception;

/**
 * A schedule that cannot be saved as written.
 *
 * <p>A cron nobody can parse, settings a job cannot use, a second target that is not there. All of
 * them are things to say to the person writing the schedule rather than to record as a failure at
 * the hour it would have run.
 */
public class ScheduleRefusedException extends RuntimeException {

    public ScheduleRefusedException(String message) {
        super(message);
    }
}
