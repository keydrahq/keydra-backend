package io.keydra.console.entity;

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
 * One line someone typed into a console.
 *
 * <p>Kept per target rather than globally: the commands worth recalling are the ones that made
 * sense against that keyspace, and mixing two targets' histories in one list makes the arrow keys
 * useless.
 *
 * <p>The line is stored as typed, with one exception: the arguments that are secrets by definition
 * are masked first. {@code AUTH} is refused outright — it re-authenticates a connection this
 * application shares — and {@code CONFIG SET requirepass}, {@code ACL SETUSER} and the credentials
 * inside {@code MIGRATE} have their values replaced before the line reaches this table. See {@code
 * CommandRedaction}.
 *
 * <p>That is as far as it can go. {@code SET session:token abc} is an ordinary command whose
 * argument is a secret, and no policy can know that — which is the reason this table is trimmed to
 * a few hundred lines per target rather than kept for ever.
 */
@Entity
@Table(
        name = "command_history",
        indexes = {
            @Index(name = "idx_command_history_owner", columnList = "user_id, connection_id, id")
        })
public class CommandHistoryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NotNull
    @Column(name = "connection_id", nullable = false)
    public Long connectionId;

    /**
     * Whoever typed it.
     *
     * <p>The history used to belong to the target, which meant anybody who could reach a console on
     * a server could read what every other operator had typed there — command lines carry key
     * names, patterns and arguments — and could delete all of it. A history is somebody's own up
     * arrow. What was run against a target is a different question, and the audit log answers it.
     *
     * <p>Nullable in the column and rarely null in practice: an instance with enforcement off has
     * nobody to attribute a line to, and there the whole table is one person's anyway.
     */
    @Column(name = "user_id")
    public Long userId;

    @NotBlank
    @Column(nullable = false, length = 4096)
    public String line;

    @NotNull
    @Column(name = "executed_at", nullable = false)
    public Instant executedAt;

    public static CommandHistoryEntry of(Long connectionId, Long userId, String line, Instant at) {
        CommandHistoryEntry entry = new CommandHistoryEntry();
        entry.connectionId = connectionId;
        entry.userId = userId;
        entry.line = line;
        entry.executedAt = at;
        return entry;
    }
}
