package io.keydra.cluster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One time something outside Keydra started or stopped answering.
 *
 * <p>A change, never an answer. Phase 49 kept the last answer and only the last, and was right
 * about what it was refusing: six rows an hour per subject, each saying what the one before it
 * said, is a table that grows with the clock and holds nothing. A row written when something
 * happens is the opposite — a handful a year for a destination that works, and worth more the older
 * it gets, because "this has been fine since March" is a sentence only a history can say.
 *
 * <p>The same shape {@code AlertEvent} has, for the same reason: a transition rather than a
 * reading.
 */
@Entity
@Table(
        name = "reachability_event",
        indexes = {
            @Index(name = "idx_reachability_event_at", columnList = "at"),
            @Index(name = "idx_reachability_event_subject", columnList = "kind, subject_id, at")
        })
public class ReachabilityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "reachability_event_seq")
    public Long id;

    /** What kind of thing, keyed the way the current answer is keyed. */
    @Column(nullable = false, length = 64)
    public String kind;

    @Column(name = "subject_id", nullable = false)
    public Long subjectId;

    /**
     * What it was called when this happened.
     *
     * <p>Stored rather than resolved on the way out. There is no foreign key to resolve it through
     * — the kinds live in different tables — and a destination somebody deleted last week still
     * stopped answering on Tuesday. It is also the name the message said at the time, so the
     * timeline and the channel agree about what a thing was called.
     */
    @Column(length = 200)
    public String name;

    @Column(nullable = false)
    public Instant at = Instant.now();

    @Column(nullable = false)
    public boolean ok;

    /** What it said when it did not answer. A sentence, never a credential. */
    @Column(length = 500)
    public String detail;
}
