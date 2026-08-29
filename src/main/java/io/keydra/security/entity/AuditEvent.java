package io.keydra.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * One thing somebody did.
 *
 * <p>Recorded for operations that change something — data, a connection profile, an ACL — and not
 * for reads. An audit log that records every page view buries the four entries a month that someone
 * will actually go looking for.
 *
 * <p>The detail field holds what was acted on, never what it was set to. A value written to Redis
 * may be a password or a token, and copying it into a second store that is queryable by every admin
 * turns an audit trail into a disclosure.
 */
@Entity
@Table(
        name = "audit_event",
        indexes = {
            @Index(name = "idx_audit_at", columnList = "at"),
            @Index(name = "idx_audit_actor", columnList = "actor"),
            @Index(name = "idx_audit_connection", columnList = "connection_id")
        })
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @Column(nullable = false)
    public Instant at;

    /** Who did it, as the identity provider named them. */
    @NotBlank
    @Column(nullable = false, length = 255)
    public String actor;

    /** What they did, as a stable identifier rather than a sentence. */
    @NotBlank
    @Column(nullable = false, length = 64)
    public String action;

    /** The target it was done to, when it was done to one. */
    @Column(name = "connection_id")
    public Long connectionId;

    /** What was acted on — a key name, a profile name, a user name. Never a value. */
    @Column(length = 1024)
    public String detail;

    /** False when the operation was refused or failed, so a denial leaves a trace too. */
    @NotNull
    @Column(nullable = false)
    public boolean succeeded;

    public static AuditEvent of(
            String actor, String action, Long connectionId, String detail, boolean succeeded) {
        AuditEvent event = new AuditEvent();
        event.at = Instant.now();
        event.actor = actor;
        event.action = action;
        event.connectionId = connectionId;
        event.detail = detail;
        event.succeeded = succeeded;
        return event;
    }
}
