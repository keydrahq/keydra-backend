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
import java.time.Instant;

/**
 * A rule changing its mind, written down.
 *
 * <p>The whole reason the phase exists: something happened at four in the morning and somebody
 * needs to know it did. The reading and the threshold are copied onto the row rather than looked up
 * later, because a rule that has since been edited would otherwise rewrite its own history.
 *
 * <p>The name of the rule is copied for the same reason. The connection id is not decoration
 * either: it is what decides who may read the row, the same way it decides who may see the target.
 */
@Entity
@Table(
        name = "alert_event",
        indexes = {
            @Index(name = "idx_alert_event_rule", columnList = "rule_id"),
            @Index(name = "idx_alert_event_at", columnList = "at")
        })
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "alert_event_seq")
    public Long id;

    @Column(name = "rule_id", nullable = false)
    public Long ruleId;

    /** What the rule was called when this happened. */
    @Column(name = "rule_name", nullable = false, length = 200)
    public String ruleName;

    @Column(name = "connection_id", nullable = false)
    public Long connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    public EventKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    public AlertMetric metric;

    /** The reading that decided it, or null when the decision was that there was no reading. */
    @Column(name = "reading")
    public Double reading;

    @Column(nullable = false)
    public double threshold;

    @Column(nullable = false)
    public Instant at = Instant.now();

    /** Where it was sent, by name, so a deleted delivery does not erase what it did. */
    @Column(name = "delivery_name", length = 200)
    public String deliveryName;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "delivery_outcome",
            nullable = false,
            length = 16,
            columnDefinition = "varchar(16)")
    public DeliveryOutcome deliveryOutcome = DeliveryOutcome.NONE;

    /** Why it did not arrive, when it did not. Never the credential it tried to use. */
    @Column(name = "delivery_detail", length = 1000)
    public String deliveryDetail;
}
