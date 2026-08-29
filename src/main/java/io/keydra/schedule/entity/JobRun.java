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
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One attempt at a scheduled job.
 *
 * <p>Written when the attempt starts, not when it finishes. A job that hangs has to be visible as
 * one that hung; recorded only on completion it would look exactly like one that never ran, and
 * "never ran" and "still running" call for opposite responses.
 */
@Entity
@Table(
        name = "job_run",
        indexes = {
            @Index(name = "idx_job_run_job", columnList = "job_id"),
            @Index(name = "idx_job_run_started", columnList = "started_at")
        })
public class JobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "job_run_seq")
    public Long id;

    @Column(name = "job_id", nullable = false)
    @NotNull
    public Long jobId;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    public Instant finishedAt;

    @Enumerated(EnumType.STRING)
    // Spelled out so no check constraint listing today's outcomes is generated: INTERRUPTED was
    // added after this table existed elsewhere, and a constraint written once is a constraint that
    // refuses the next word for it.
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public RunOutcome outcome = RunOutcome.RUNNING;

    /**
     * Which instance ran it.
     *
     * <p>Only one does — the scheduler asks whether it holds the chores before it starts anything —
     * but which one matters after the fact. A row still saying RUNNING under the name of an
     * instance nobody has heard from is a run that stopped; the same row with no name on it is
     * indistinguishable from a job that is still going somewhere else.
     */
    @Column(name = "instance_id", length = 64)
    public String instanceId;

    /**
     * What it did, in one line somebody can read.
     *
     * <p>"Removed 41,204 keys" rather than a status code: the question anybody opens a run history
     * to answer is what happened, and a number is the answer.
     */
    @Column(length = 500)
    public String detail;

    /** Whether somebody asked for it rather than the clock. */
    @Column(name = "was_manual", nullable = false)
    public boolean wasManual = false;
}
