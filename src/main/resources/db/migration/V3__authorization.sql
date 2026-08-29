-- Who may see which server, and do what to it.
--
-- The model is in docs/AUTHORIZATION.md: subjects, scopes, roles and permissions, joined by
-- grants. Two graphs are stored here — groups containing groups, and server groups
-- containing server groups — and both are walked at resolution time rather than flattened,
-- so an organisation states its structure once.

CREATE TABLE app_user (
    id            BIGINT       NOT NULL,
    username      VARCHAR(200) NOT NULL,
    display_name  VARCHAR(200),
    email         VARCHAR(320),
    -- A name rather than an enum: providers are rows a deployment adds.
    provider      VARCHAR(64)  NOT NULL,
    external_id   VARCHAR(320),
    -- Argon2id, and null for anyone a provider vouches for.
    password_hash VARCHAR(256),
    enabled       BOOLEAN      NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    last_seen_at  TIMESTAMP,
    CONSTRAINT app_user_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_app_user_username ON app_user (username);
CREATE INDEX idx_app_user_external ON app_user (provider, external_id);

CREATE TABLE user_group (
    id          BIGINT       NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    -- Set when a provider's claim mapping maintains the group rather than a person.
    managed_by  VARCHAR(64),
    CONSTRAINT user_group_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_user_group_name ON user_group (name);

-- One edge of the subject graph. Exactly one member column is set: a row with both would
-- be two edges pretending to be one, and a row with neither is an edge to nowhere.
CREATE TABLE group_membership (
    id              BIGINT NOT NULL,
    group_id        BIGINT NOT NULL,
    member_user_id  BIGINT,
    member_group_id BIGINT,
    CONSTRAINT group_membership_pkey PRIMARY KEY (id),
    CONSTRAINT group_membership_one_member CHECK (
        (member_user_id IS NOT NULL AND member_group_id IS NULL)
        OR (member_user_id IS NULL AND member_group_id IS NOT NULL)
    ),
    CONSTRAINT group_membership_group_fk FOREIGN KEY (group_id)
        REFERENCES user_group (id) ON DELETE CASCADE,
    CONSTRAINT group_membership_user_fk FOREIGN KEY (member_user_id)
        REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT group_membership_member_group_fk FOREIGN KEY (member_group_id)
        REFERENCES user_group (id) ON DELETE CASCADE
);

CREATE INDEX idx_group_membership_group ON group_membership (group_id);
CREATE INDEX idx_group_membership_user ON group_membership (member_user_id);
CREATE INDEX idx_group_membership_member_group ON group_membership (member_group_id);

CREATE TABLE server_group (
    id          BIGINT       NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    -- A tree rather than a graph: a server belongs somewhere, and a group in two places
    -- would make "which environment is this" ambiguous.
    parent_id   BIGINT,
    CONSTRAINT server_group_pkey PRIMARY KEY (id),
    CONSTRAINT server_group_parent_fk FOREIGN KEY (parent_id)
        REFERENCES server_group (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_server_group_name ON server_group (name);

CREATE TABLE server_group_member (
    id            BIGINT NOT NULL,
    group_id      BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    CONSTRAINT server_group_member_pkey PRIMARY KEY (id),
    CONSTRAINT server_group_member_group_fk FOREIGN KEY (group_id)
        REFERENCES server_group (id) ON DELETE CASCADE,
    CONSTRAINT server_group_member_connection_fk FOREIGN KEY (connection_id)
        REFERENCES connection_profile (id) ON DELETE CASCADE,
    -- A target is in a group once. Twice would double every grant that reached it.
    CONSTRAINT server_group_member_unique UNIQUE (group_id, connection_id)
);

CREATE INDEX idx_server_group_member_group ON server_group_member (group_id);
CREATE INDEX idx_server_group_member_connection ON server_group_member (connection_id);

CREATE TABLE role_definition (
    id          BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    is_built_in BOOLEAN      NOT NULL,
    CONSTRAINT role_definition_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_role_definition_name ON role_definition (name);

CREATE TABLE role_permission (
    role_id    BIGINT      NOT NULL,
    permission VARCHAR(64) NOT NULL,
    CONSTRAINT role_permission_role_fk FOREIGN KEY (role_id)
        REFERENCES role_definition (id) ON DELETE CASCADE
);

CREATE INDEX idx_role_permission_role ON role_permission (role_id);

-- The sentence the whole model is built on. There is no negative form: absence is the
-- denial, which is why nothing here says "deny".
CREATE TABLE authz_grant (
    id           BIGINT       NOT NULL,
    subject_type VARCHAR(16)  NOT NULL,
    subject_id   BIGINT       NOT NULL,
    scope_type   VARCHAR(16)  NOT NULL,
    -- Null for the instance scope, which is one thing and needs no identifier.
    scope_id     BIGINT,
    role_id      BIGINT       NOT NULL,
    granted_at   TIMESTAMP    NOT NULL,
    granted_by   VARCHAR(200),
    CONSTRAINT authz_grant_pkey PRIMARY KEY (id),
    CONSTRAINT authz_grant_role_fk FOREIGN KEY (role_id)
        REFERENCES role_definition (id) ON DELETE CASCADE,
    CONSTRAINT authz_grant_instance_has_no_scope CHECK (
        (scope_type = 'INSTANCE' AND scope_id IS NULL)
        OR (scope_type <> 'INSTANCE' AND scope_id IS NOT NULL)
    )
);

CREATE INDEX idx_grant_subject ON authz_grant (subject_type, subject_id);
CREATE INDEX idx_grant_scope ON authz_grant (scope_type, scope_id);

-- Increment 50 to match Hibernate's default allocation size, as elsewhere in this schema.
CREATE SEQUENCE app_user_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE user_group_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE group_membership_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE server_group_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE server_group_member_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE role_definition_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE authz_grant_seq START WITH 1 INCREMENT BY 50;
