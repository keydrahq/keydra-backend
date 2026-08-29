-- A link that lets one person set one password, once, before a deadline.
--
-- The token itself is never here; its SHA-256 is. The link *is* the credential — whoever holds the
-- string can take the account it names — so it is stored the way a password is, and a leak of this
-- table hands nobody an account.
--
-- One row covers both an invitation and a password reset. They are the same mechanism seen from two
-- sides, and a second table would be a second place to get token handling wrong.
CREATE TABLE account_invitation
(
    id          BIGINT       NOT NULL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL,
    purpose     VARCHAR(16)  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(200),
    accepted_at TIMESTAMPTZ
);

-- Unique because redeeming a link looks it up by this and must find one row or none.
CREATE UNIQUE INDEX idx_invitation_token ON account_invitation (token_hash);
-- Sending a new link ends whatever the account already had, which is a lookup by account.
CREATE INDEX idx_invitation_user ON account_invitation (user_id);

CREATE SEQUENCE account_invitation_seq START WITH 1 INCREMENT BY 50;
