package io.keydra.keys.entity;

import io.keydra.keys.dto.MigrationJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A migration, written down.
 *
 * <p>Added because the alternative was worse than incomplete: jobs lived in memory, so a restart
 * did not merely lose the history — it made an interrupted migration indistinguishable from one
 * that never ran. Somebody deciding whether to start a copy again has to know which of those
 * happened, and the answer was being thrown away by the thing that knew it.
 *
 * <p>The counters are checkpointed rather than written per batch. A row updated on every batch
 * would be a database write per two hundred keys; what matters after an interruption is the order
 * of magnitude, and the row says when it was last written.
 *
 * <p>The id is the job's own — a UUID made when the job started, so the row, the notifications and
 * the cancel endpoint all name the same thing.
 */
@Entity
@Table(
        name = "key_migration",
        indexes = {
            @Index(name = "idx_key_migration_started", columnList = "started_at"),
            @Index(name = "idx_key_migration_source", columnList = "source_connection_id")
        })
public class MigrationRun {

    @Id
    @Column(length = 36)
    public String id;

    @Column(name = "source_connection_id", nullable = false)
    public Long sourceConnectionId;

    @Column(name = "target_connection_id", nullable = false)
    public Long targetConnectionId;

    /** The glob the keys were taken with. Named for what it is; {@code match} is reserved. */
    @Column(name = "match_pattern", length = 500)
    public String matchPattern;

    /**
     * How many keys were expected to move, when anything could say.
     *
     * <p>Stored as well as broadcast so a job watched from a second Keydra — or after a reload —
     * draws the same bar as the one that started it. Null for a migration taken with a glob, which
     * has no total until it has finished.
     */
    @Column(name = "total_keys")
    public Long totalKeys;

    @Column(nullable = false)
    public long scanned;

    @Column(nullable = false)
    public long migrated;

    @Column(nullable = false)
    public long skipped;

    @Column(nullable = false)
    public long failed;

    @Column(nullable = false)
    public long dropped;

    public long deleted;

    /** What the target said about the first refusal, or what ended the job. */
    @Column(length = 1000)
    public String reason;

    @Enumerated(EnumType.STRING)
    // Spelled out so no check constraint listing today's states is generated: it is written
    // once and never widened, and INTERRUPTED was added after this table existed elsewhere.
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    public MigrationJob.State state = MigrationJob.State.RUNNING;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    public Instant finishedAt;

    /** Who asked for it, as a name so it survives the account being recreated. */
    @Column(name = "started_by", length = 200)
    public String startedBy;

    /**
     * Which instance is doing the walking.
     *
     * <p>So the sweep that marks interrupted migrations after a restart sweeps up after this
     * process and not after another one that is, at that moment, still working through its keys.
     */
    @Column(name = "instance_id", length = 64)
    public String instanceId;

    /**
     * What the job was asked to do, as the request that started it.
     *
     * <p>Stored because the row was a description of a migration rather than a recipe for one: it
     * said what was being moved and how far it had got, and nothing about the prefixes, the script,
     * the pacing or whether the source keys were being deleted. Another instance finding this row
     * abandoned could see that work had stopped and had no way to carry it on.
     *
     * <p>JSON rather than a column each, because the shape of a request is the migration domain's
     * to change and a column per field would make every new option a migration of this table. What
     * matters here is that it round-trips.
     */
    @Column(columnDefinition = "text")
    public String request;

    /**
     * When the instance doing the walking last said it was still doing it.
     *
     * <p>Touched at every checkpoint, which is what makes "abandoned" answerable. A name on a row
     * says who started the work; only a time says whether they are still at it — and an instance
     * that was killed leaves both the name and the RUNNING state exactly as they were.
     */
    @Column(name = "claimed_at")
    public Instant claimedAt = Instant.now();

    /**
     * How many times this job has changed hands.
     *
     * <p>On the row rather than in a log, because a migration that has been taken over twice is
     * saying something about the instances rather than about itself, and that is worth seeing next
     * to the job it happened to.
     */
    @Column(nullable = false)
    public int resumed;

    /** The row as the API describes a job. */
    public MigrationJob toJob() {
        return new MigrationJob(
                id,
                sourceConnectionId,
                targetConnectionId,
                matchPattern,
                totalKeys,
                scanned,
                migrated,
                skipped,
                failed,
                dropped,
                deleted,
                reason,
                state,
                startedAt,
                finishedAt,
                startedBy,
                resumed);
    }

    /** Copies the counters and the ending off a snapshot the running job took of itself. */
    public void apply(MigrationJob job) {
        totalKeys = job.total();
        scanned = job.scanned();
        migrated = job.migrated();
        skipped = job.skipped();
        failed = job.failed();
        dropped = job.dropped();
        deleted = job.deleted();
        reason = job.reason();
        state = job.state();
        if (job.isFinished() && finishedAt == null) {
            finishedAt = Instant.now();
        }
    }
}
