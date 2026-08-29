package io.keydra.keys.dto;

/**
 * A migration in progress, or one that has finished.
 *
 * <p>Every number is cumulative and the same record is broadcast repeatedly as the job runs, so a
 * page that joins late or reloads sees the current state rather than having to add up the events it
 * happened to catch.
 *
 * @param id identifies the job for cancelling it
 * @param sourceConnectionId the target being read
 * @param targetConnectionId the target being written
 * @param match the glob the keys were taken with
 * @param total how many keys are expected to move, or null when nothing can say.
 *     <p>Known when the caller named the keys, and when the whole database is being moved — the
 *     store itself counts those. A glob has no such answer: the store cannot say how many keys
 *     match one without walking the keyspace, which is the job itself. Null therefore means "no
 *     denominator exists", not "not measured yet", and a progress bar drawn from {@code scanned}
 *     instead would sit at full for the whole run — every key found has already been dealt with by
 *     the time the next one is found.
 * @param scanned keys found so far — not a total, because the walk is still going
 * @param migrated keys the target accepted
 * @param skipped keys already on the target, left alone because replacing was not asked for
 * @param failed keys the target refused
 * @param dropped keys a script decided not to move. Its own count rather than folded into the
 *     skipped ones, which mean something else: a key already on the target was left alone, and a
 *     key a script turned down was never offered. Without it the difference between scanned and
 *     handled would silently absorb them, and the dialog explains that difference as keys that
 *     expired while the walk ran — which would then be a wrong explanation stated confidently.
 * @param deleted keys removed from the source after the target accepted them
 * @param reason what the target said about the first refusal, or what ended the job
 * @param state where the job is
 * @param startedAt when it began
 * @param finishedAt when it stopped, or null while it is still going
 * @param startedBy who asked for it, as a name rather than an id so it reads as itself later
 * @param resumed how many times another instance has picked this up after the one running it went
 *     away — nought for almost every job, and the sentence that explains a progress bar somebody
 *     watched go back to the beginning
 */
public record MigrationJob(
        String id,
        long sourceConnectionId,
        long targetConnectionId,
        String match,
        Long total,
        long scanned,
        long migrated,
        long skipped,
        long failed,
        long dropped,
        long deleted,
        String reason,
        State state,
        java.time.Instant startedAt,
        java.time.Instant finishedAt,
        String startedBy,
        int resumed) {

    public enum State {
        RUNNING,
        DONE,
        CANCELLED,
        FAILED,
        /**
         * Keydra stopped while this was running.
         *
         * <p>Its own state rather than a failure, because it says something different and calls for
         * something different: nothing refused anything, the process simply went away, and what was
         * already written is written. Recorded at the next startup, because a job that was
         * interrupted has nobody left to record it — and "interrupted" and "never happened" must
         * not look alike in a history somebody consults to decide whether to run it again.
         */
        INTERRUPTED
    }

    public boolean isFinished() {
        return state != State.RUNNING;
    }
}
