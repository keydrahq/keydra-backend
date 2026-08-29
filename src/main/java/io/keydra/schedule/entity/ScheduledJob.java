package io.keydra.schedule.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Work arranged to happen on its own.
 *
 * <p>A job, a target, a cadence and a switch. Everything a particular kind of work needs beyond
 * that — which keys, which second target, where the file goes — is in {@link #settings}, because
 * the jobs have almost nothing in common except being worth repeating, and a column per job type
 * would be a table that grows every time one is added.
 *
 * <p>{@link #createdBy} is not bookkeeping. It is who the work runs as: the permission is checked
 * again at every run, so an arrangement made by somebody who has since lost the access stops rather
 * than carrying on as a way of keeping it.
 */
@Entity
@Table(
        name = "scheduled_job",
        indexes = {
            @Index(name = "idx_scheduled_job_connection", columnList = "connection_id"),
            @Index(name = "idx_scheduled_job_enabled", columnList = "enabled")
        })
public class ScheduledJob {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "scheduled_job_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    /** The target the work happens to. A schedule is always about one. */
    @Column(name = "connection_id", nullable = false)
    @NotNull
    public Long connectionId;

    @Enumerated(EnumType.STRING)
    // Spelled out for the same reason RoleDefinition's permission column is: a generated
    // check constraint lists the values that existed when the table was made and is never
    // widened by a schema update, so adding a kind of work would make every insert fail.
    @Column(name = "job_type", nullable = false, length = 32, columnDefinition = "varchar(32)")
    @NotNull
    public JobType jobType;

    /**
     * When it runs, as a cron expression.
     *
     * <p>Cron rather than an interval, because what people actually want is "at three in the
     * morning" rather than "every twenty-four hours" — and the two are only the same until
     * something restarts.
     */
    @Column(nullable = false, length = 120)
    @NotBlank
    public String cron;

    @Column(nullable = false)
    public boolean enabled = true;

    /** What this kind of work needs, as JSON. Read by the handler and by nothing else. */
    @Column(nullable = false, length = 4000)
    public String settings = "{}";

    /**
     * Whose access this runs with.
     *
     * <p>A username rather than an id, so it survives the account being recreated under the same
     * name and reads as itself in a log.
     */
    @Column(name = "created_by", length = 200)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    /** What happened the last time, so a list of schedules answers "is this working". */
    @Column(name = "last_run_at")
    public Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_outcome", length = 16)
    public RunOutcome lastOutcome;
}
