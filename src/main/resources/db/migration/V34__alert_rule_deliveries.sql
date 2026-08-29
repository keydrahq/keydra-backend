-- Where a rule announces itself, which is now a list.
--
-- One destination per rule meant writing the rule twice to tell two people, and two copies of one
-- decision drift: somebody raises the threshold on one, or disables one while investigating, and
-- the same condition now alerts two channels differently — which is worse than alerting one,
-- because it looks like two conditions.
CREATE TABLE alert_rule_delivery (
    rule_id     BIGINT NOT NULL REFERENCES alert_rule (id) ON DELETE CASCADE,
    -- No cascade to the delivery on purpose. Removing a channel a rule points at is refused while
    -- the rule points at it, because a rule that quietly started firing into nothing is the one
    -- failure an alert must not have.
    delivery_id BIGINT NOT NULL REFERENCES alert_delivery (id),
    PRIMARY KEY (rule_id, delivery_id)
);

CREATE INDEX idx_alert_rule_delivery_delivery ON alert_rule_delivery (delivery_id);

-- Whatever each rule already pointed at becomes the first entry of its own list.
INSERT INTO alert_rule_delivery (rule_id, delivery_id)
SELECT id, delivery_id FROM alert_rule WHERE delivery_id IS NOT NULL;

ALTER TABLE alert_rule DROP COLUMN delivery_id;
