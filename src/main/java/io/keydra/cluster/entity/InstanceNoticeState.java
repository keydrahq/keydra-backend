package io.keydra.cluster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What has already been said about Keydra's own condition.
 *
 * <p>One row per subject, and there is exactly one subject: whether anybody is doing the chores. A
 * table rather than a column on something, because the thing it is about is the <em>absence</em> of
 * a row — a lease nobody holds has nowhere to hang a flag.
 *
 * <p>Read and written by every instance rather than by the leader. A check that only the leader ran
 * would be a smoke alarm wired to the circuit it is watching, so the database decides who speaks
 * instead: the update that changes {@link #firing} names the value it expects to find, and exactly
 * one instance wins it.
 */
@Entity
@Table(name = "instance_notice_state")
public class InstanceNoticeState {

    /** What this row is about. There is one, and it is the chores. */
    public static final String CHORES = "chores";

    @Id
    @Column(length = 64)
    public String subject;

    /** Whether the bad news has been sent and the good news has not. */
    @Column(nullable = false)
    public boolean firing;

    @Column(nullable = false)
    public Instant since = Instant.now();
}
