package io.keydra.alerts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

/**
 * One place a rule announces itself.
 *
 * <p>A list rather than a column, because one destination per rule meant writing the rule twice to
 * tell two people — and two copies of one decision drift. Somebody raises the threshold on one, or
 * disables one while investigating, and the same condition now alerts two channels differently,
 * which is worse than alerting one because it looks like two conditions.
 *
 * <p>The row is deleted with its rule and never with its delivery: removing a channel a rule points
 * at is refused while the rule points at it, because a rule that quietly started firing into
 * nothing is the one failure an alert must not have.
 */
@Entity
@Table(name = "alert_rule_delivery")
@IdClass(AlertRuleDelivery.Key.class)
public class AlertRuleDelivery {

    @Id
    @Column(name = "rule_id", nullable = false)
    public Long ruleId;

    @Id
    @Column(name = "delivery_id", nullable = false)
    public Long deliveryId;

    /** The pair that identifies a row: which rule, and which destination. */
    public static class Key implements Serializable {
        public Long ruleId;
        public Long deliveryId;

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(ruleId, key.ruleId)
                    && Objects.equals(deliveryId, key.deliveryId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ruleId, deliveryId);
        }
    }
}
