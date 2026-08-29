package io.keydra.approvals.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * An operation that has been asked for and not yet agreed to.
 *
 * <p>The row is the operation rather than a note about one. Everything it needs is written down
 * when it is asked for and exists nowhere else, so what an approver reads is what runs. The
 * alternative — an unlock the requester then re-uses — lets the glob, the key list or {@code
 * replace} change between the agreement and the act, which would make an approval a signature on a
 * document somebody rewrote afterwards.
 */
@Entity
@Table(
        name = "approval_request",
        indexes = {
            @Index(name = "idx_approval_request_state", columnList = "state"),
            @Index(name = "idx_approval_request_connection", columnList = "connection_id")
        })
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "approval_request_seq")
    public Long id;

    @Enumerated(EnumType.STRING)
    // Spelled out for the reason ScheduledJob.jobType is: a generated check constraint lists the
    // values that existed when the table was made and is never widened, so adding a kind would
    // make every insert fail.
    @Column(name = "kind", nullable = false, length = 32, columnDefinition = "varchar(32)")
    @NotNull
    public ApprovalKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public ApprovalState state = ApprovalState.PENDING;

    /** The target the operation is about. */
    @Column(name = "connection_id", nullable = false)
    @NotNull
    public Long connectionId;

    /**
     * The other end, where there is one.
     *
     * <p>Only a migration has two, and both of them count — writing into a target can overwrite it
     * and moving keys off one empties it — so whoever approves has to hold the permission on both.
     */
    @Column(name = "second_connection_id")
    public Long secondConnectionId;

    /**
     * What will happen, as JSON, encrypted.
     *
     * <p>Encrypted because of what is in it: for an import the dumped values themselves, and for a
     * bulk delete a list of key names, which everything else in this application already treats as
     * the contents of somebody's target. Read by the handler that will carry it out and by nothing
     * else.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "payload", nullable = false, columnDefinition = "text")
    @NotNull
    public String payload;

    /**
     * Whose access it runs with.
     *
     * <p>Resolved again at the moment it runs, like a schedule's author and for the same reason: a
     * way of doing something later must not become a way of keeping access that has been taken
     * away.
     */
    @Column(name = "requested_by", length = 200)
    public String requestedBy;

    @Column(name = "requested_at", nullable = false)
    public Instant requestedAt = Instant.now();

    /** When it stops being answerable at all. */
    @Column(name = "expires_at", nullable = false)
    @NotNull
    public Instant expiresAt;

    @Column(name = "decided_by", length = 200)
    public String decidedBy;

    @Column(name = "decided_at")
    public Instant decidedAt;

    /**
     * Why it was declined, or what happened when it ran.
     *
     * <p>One column, because a request has one ending and two would be a row that could describe
     * itself twice.
     */
    @Column(name = "detail", length = 1000)
    public String detail;
}
