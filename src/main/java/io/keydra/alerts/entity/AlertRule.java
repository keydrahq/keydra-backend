package io.keydra.alerts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * A condition somebody wants to hear about.
 *
 * <p>A target, a metric, a side, a number and a duration. Nothing about who to tell beyond which
 * delivery to use, and nothing about how often to repeat, because there is no repeating: only the
 * transitions are events, so a rule that has been firing since Tuesday has sent one message.
 *
 * <p>An enabled rule is also a reason to keep its target sampled. Sampling is opt-in and costs a
 * round trip per interval, and writing a rule is the clearest possible way of opting in — otherwise
 * the rules would only work while somebody had the dashboard open, which is precisely when they are
 * not needed.
 */
@Entity
@Table(
        name = "alert_rule",
        indexes = {
            @Index(name = "idx_alert_rule_connection", columnList = "connection_id"),
            @Index(name = "idx_alert_rule_enabled", columnList = "enabled")
        })
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "alert_rule_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    /** The target it watches. A rule is always about one. */
    @Column(name = "connection_id", nullable = false)
    @NotNull
    public Long connectionId;

    @Enumerated(EnumType.STRING)
    // Spelled out for the same reason every other enum column here is: a generated check
    // constraint lists the values that existed when the table was made and is never widened,
    // so adding a metric would make every insert fail.
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    @NotNull
    public AlertMetric metric;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public Comparison comparison = Comparison.ABOVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public AlertBasis basis = AlertBasis.ABSOLUTE;

    /**
     * The number, read as the basis says to read it.
     *
     * <p>An amount in the metric's own unit for an absolute rule, and a percentage of the baseline
     * for one written against the past.
     */
    @Column(nullable = false)
    public double threshold;

    /**
     * How wide the window the baseline is taken over is.
     *
     * <p>An hour of last Tuesday rather than the instant a week ago: a single reading from the past
     * is a spike or a trough as easily as it is normal, and what a rule wants to compare with is
     * what the server was generally doing.
     */
    @Column(name = "baseline_window_seconds", nullable = false)
    @PositiveOrZero
    public int baselineWindowSeconds = 3600;

    /**
     * How far back that window sits, from now.
     *
     * <p>Zero means the window immediately before this moment — "busier than it has been this
     * hour". A day or a week puts it at the same hour of an earlier one, which is the comparison
     * people actually make out loud.
     */
    @Column(name = "baseline_offset_seconds", nullable = false)
    @PositiveOrZero
    public int baselineOffsetSeconds;

    /**
     * How long the condition has to hold before anybody is told.
     *
     * <p>Zero is allowed and means "the first reading is enough", which is the right answer for a
     * condition that cannot flap.
     */
    @Column(name = "for_seconds", nullable = false)
    @PositiveOrZero
    public int forSeconds;

    @Column(nullable = false)
    public boolean enabled = true;

    /**
     * Where a message goes, and an empty list is a perfectly good answer.
     *
     * <p>Empty means the notification hub alone: the event is recorded and broadcast either way, so
     * a rule with no delivery still shows up in the application the moment it fires. A delivery is
     * the second copy, for the hours when nobody has the application open.
     *
     * <p>Not a column and not a mapped collection: {@code AlertRuleRepository} fills it in on every
     * read and writes it on every save. The shape phase 48 settled on for a backup destination's
     * recipients, and for the same reason — a mapped collection here is a lazy load on a reactive
     * session, and this list is small, always read whole and always written whole.
     */
    @Transient public java.util.List<Long> deliveryIds = java.util.List.of();

    /** Whose access wrote it, kept as a name so it reads as itself in a history. */
    @Column(name = "created_by", length = 200)
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
