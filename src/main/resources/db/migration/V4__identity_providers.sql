-- Phase 9.5: places people can sign in from, configured while running.
--
-- Rows rather than environment variables. The endpoints are stored beside the issuer
-- because they are discovered once, when the provider is saved: a sign-in that had to
-- fetch a discovery document first would fail whenever the provider was briefly slow,
-- and a stale document is a thing an administrator can see and refresh.

CREATE TABLE identity_provider
(
    id                     BIGINT       NOT NULL,
    provider_key           VARCHAR(64)  NOT NULL,
    display_name           VARCHAR(200) NOT NULL,
    kind                   VARCHAR(16)  NOT NULL,
    enabled                BOOLEAN      NOT NULL,
    sort_order             INTEGER      NOT NULL,
    issuer                 VARCHAR(500),
    client_id              VARCHAR(300) NOT NULL,
    -- Encrypted at rest by EncryptedStringConverter, which is why this is far wider
    -- than the secret it holds.
    client_secret          VARCHAR(2000),
    scopes                 VARCHAR(500) NOT NULL,
    authorization_endpoint VARCHAR(500),
    token_endpoint         VARCHAR(500),
    userinfo_endpoint      VARCHAR(500),
    subject_claim          VARCHAR(100) NOT NULL,
    username_claim         VARCHAR(100) NOT NULL,
    email_claim            VARCHAR(100),
    name_claim             VARCHAR(100),
    groups_claim           VARCHAR(100),
    auto_create_users      BOOLEAN      NOT NULL,
    CONSTRAINT identity_provider_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_identity_provider_key ON identity_provider (provider_key);

CREATE TABLE provider_group_mapping
(
    id          BIGINT       NOT NULL,
    provider_id BIGINT       NOT NULL,
    claim_value VARCHAR(300) NOT NULL,
    group_id    BIGINT       NOT NULL,
    CONSTRAINT provider_group_mapping_pkey PRIMARY KEY (id),
    CONSTRAINT provider_group_mapping_provider_fk FOREIGN KEY (provider_id)
        REFERENCES identity_provider (id) ON DELETE CASCADE,
    CONSTRAINT provider_group_mapping_group_fk FOREIGN KEY (group_id)
        REFERENCES user_group (id) ON DELETE CASCADE
);

CREATE INDEX idx_provider_group_mapping_provider ON provider_group_mapping (provider_id);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE identity_provider_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE provider_group_mapping_seq START WITH 1 INCREMENT BY 50;
