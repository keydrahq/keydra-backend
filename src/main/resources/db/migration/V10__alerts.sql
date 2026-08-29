-- Phase 15: noticing without somebody watching.
--
-- Three tables. A rule is a condition somebody wants to hear about; a delivery is somewhere
-- outside to hear about it; an event is a rule changing its mind, which is the only thing
-- worth writing down. A rule that has been firing all night wrote one row.
--
-- What is deliberately not here: the state of a rule. Where a rule stands is a fact about the
-- last few minutes rather than about the configuration, and a column for it would be a write
-- per rule per reading for something the next reading replaces. It lives in memory, and a
-- restart re-establishes it within each rule's own duration.

CREATE TABLE alert_delivery
(
    id           BIGINT       NOT NULL,
    name         VARCHAR(200) NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    enabled      BOOLEAN      NOT NULL,
    -- Encrypted at rest by EncryptedStringConverter, which is why these are far wider than
    -- what they hold. The address is among them: a webhook URL carries its authorisation in
    -- its path, so anybody holding the string can post as this application.
    url          VARCHAR(2000),
    url_host     VARCHAR(255),
    header_name  VARCHAR(200),
    header_value VARCHAR(2000),
    smtp_host    VARCHAR(255),
    smtp_port    INTEGER,
    smtp_tls     BOOLEAN      NOT NULL,
    username     VARCHAR(200),
    password     VARCHAR(1000),
    from_address VARCHAR(320),
    to_addresses VARCHAR(2000),
    CONSTRAINT alert_delivery_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_alert_delivery_name ON alert_delivery (name);

CREATE TABLE alert_rule
(
    id            BIGINT           NOT NULL,
    name          VARCHAR(200)     NOT NULL,
    connection_id BIGINT           NOT NULL,
    metric        VARCHAR(32)      NOT NULL,
    comparison    VARCHAR(16)      NOT NULL,
    threshold     DOUBLE PRECISION NOT NULL,
    -- How long the condition has to hold before anybody is told. Zero means the first reading
    -- is enough, which is right for a condition that cannot flap.
    for_seconds   INTEGER          NOT NULL,
    enabled       BOOLEAN          NOT NULL,
    delivery_id   BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT alert_rule_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_alert_rule_connection ON alert_rule (connection_id);
CREATE INDEX idx_alert_rule_enabled ON alert_rule (enabled);

CREATE TABLE alert_event
(
    id               BIGINT           NOT NULL,
    rule_id          BIGINT           NOT NULL,
    -- Copied rather than joined: a rule that has since been edited or deleted must not be
    -- able to rewrite what it once said.
    rule_name        VARCHAR(200)     NOT NULL,
    connection_id    BIGINT           NOT NULL,
    kind             VARCHAR(16)      NOT NULL,
    metric           VARCHAR(32)      NOT NULL,
    reading          DOUBLE PRECISION,
    threshold        DOUBLE PRECISION NOT NULL,
    at               TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    delivery_name    VARCHAR(200),
    delivery_outcome VARCHAR(16)      NOT NULL,
    delivery_detail  VARCHAR(1000),
    CONSTRAINT alert_event_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_alert_event_rule ON alert_event (rule_id);
CREATE INDEX idx_alert_event_at ON alert_event (at);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE alert_delivery_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE alert_rule_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE alert_event_seq START WITH 1 INCREMENT BY 50;
