-- One row per signed-in browser.
--
-- Before this a session was a cookie and nothing else: encrypted, signed and expiring, but with no
-- record on the server that it had been issued. So there was no list of who was signed in, no way
-- to end one session without ending them all, and a stolen cookie was good until it expired —
-- changing a password did not stop it, because there was nothing to stop.
--
-- The id is a UUID rather than a sequence because it travels to the browser: a number that
-- increments tells whoever holds one roughly how many people use this Keydra.
--
-- What is kept about where a session came from is deliberately little. The user agent is whatever
-- the client said it was, and the network is the address with its last part removed — enough for
-- somebody to recognise their own laptop, not enough to be a record of their movements.
CREATE TABLE user_session
(
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    issued_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    user_agent   VARCHAR(400),
    network      VARCHAR(64)
);

-- Listing somebody's sessions, and ending all of them, are both lookups by account.
CREATE INDEX idx_user_session_user ON user_session (user_id);
-- The sweep that removes what has lapsed reads by expiry.
CREATE INDEX idx_user_session_expires ON user_session (expires_at);
