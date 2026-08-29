-- A second factor, and the codes that get somebody back in without their phone.
--
-- Two tables rather than columns on app_user: a factor has a lifecycle the account does not — it is
-- begun, then confirmed, and it can be removed while the account stays — and the row holding a
-- password hash and the row holding a TOTP secret being two rows is worth something on the day one
-- of them is read by somebody who should not have.
CREATE TABLE user_second_factor (
    user_id      BIGINT NOT NULL PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    -- The shared secret, base32, encrypted at rest. It cannot be hashed: verifying a code means
    -- computing one, which means having the secret back. See docs/DATA-AT-REST.md.
    secret       VARCHAR(512) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    -- Null while the pairing is only begun. Nothing is enforced until this is set, so somebody who
    -- opens the page and scans nothing has not locked themselves out.
    confirmed_at TIMESTAMPTZ
);

CREATE TABLE user_recovery_code (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- SHA-256 of the code, hex. Not Argon2, and for the opposite of the usual reason: Argon2 is
    -- slow because a password is guessable, and this is eighty bits the machine chose.
    code_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- A code works once; a used one stays as the record that it was used.
    used_at    TIMESTAMPTZ,
    PRIMARY KEY (user_id, code_hash)
);

CREATE INDEX idx_recovery_code_user ON user_recovery_code (user_id);
