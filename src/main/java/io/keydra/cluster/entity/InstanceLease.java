package io.keydra.cluster.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The right to do a job that must only be done once, held for a few seconds at a time.
 *
 * <p>One row per role. Whoever holds it renews it; an instance that stops renewing loses it to
 * whoever asks next, which is what makes a crash recoverable without anybody deciding anything.
 *
 * <p>The expiry is written by the database's clock rather than by the instance's, and that is the
 * whole trick: two machines whose clocks differ by a minute still agree about whose lease has run
 * out, because neither of them is the one being asked.
 */
@Entity
@Table(name = "instance_lease")
public class InstanceLease {

    /** What the lease is for. One row per kind of work that must happen once. */
    @Id
    @Column(length = 64)
    public String role;

    /** Which instance holds it, as the id that instance announced itself with. */
    @Column(nullable = false, length = 64)
    public String holder;

    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
