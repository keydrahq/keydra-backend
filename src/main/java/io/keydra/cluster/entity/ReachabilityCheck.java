package io.keydra.cluster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The last time something outside Keydra was asked whether it was there.
 *
 * <p>One row per thing, replaced each time it is asked. The last answer only: a history of
 * reachability is a different page, and one whose rows are worth nothing an hour later.
 *
 * <p>No foreign key to what it describes. The kinds live in different tables, so there is nothing
 * one key could point at — and a row about something since deleted stops being reported on the next
 * walk, which is cheaper and less surprising than a cascade.
 */
@Entity
@Table(name = "reachability_check")
@IdClass(ReachabilityCheck.Key.class)
public class ReachabilityCheck {

    @Id
    @Column(nullable = false, length = 64)
    public String kind;

    @Id
    @Column(name = "subject_id", nullable = false)
    public Long subjectId;

    @Column(name = "checked_at", nullable = false)
    public Instant checkedAt = Instant.now();

    @Column(nullable = false)
    public boolean ok;

    /** What it said, or why it did not. A sentence, never a credential. */
    @Column(length = 500)
    public String detail;

    /** What identifies a row: what kind of thing, and which one. */
    public static class Key implements Serializable {
        public String kind;
        public Long subjectId;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(kind, key.kind)
                    && Objects.equals(subjectId, key.subjectId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, subjectId);
        }
    }
}
