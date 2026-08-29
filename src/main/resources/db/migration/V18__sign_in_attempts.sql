-- Every attempt to sign in, whether it worked or not.
--
-- Two things needed this and neither could have it before. The first is a limit: without a record
-- of what has just been tried, a password form accepts guesses for as long as somebody cares to
-- make them, and the Argon2 cost that makes each guess expensive for an attacker makes it equally
-- expensive for the server — the defence and the denial of service are the same parameter. The
-- second is noticing: an account signed into from a network it has never been signed into from, or
-- from two countries an hour apart, is worth telling somebody about, and there is nothing to
-- compare against without a history to compare with.
--
-- What is kept follows the rule user_session already set. The network is the address with its last
-- part removed, not the address: enough to say "that is not where I work", not a record of
-- somebody's movements. The country is two letters and is resolved while the request is in flight,
-- from an address that is then discarded — so an instance with no geography database stores no
-- country and loses nothing else.
--
-- The username is kept as typed, including when no such account exists. That is not carelessness:
-- one source trying forty usernames is the shape of credential stuffing, and it is invisible if
-- attempts against accounts that do not exist are not written down. It is also why user_id is
-- nullable and why deleting an account leaves the attempts behind with the link cut rather than
-- deleting them — the history of an attack on an account outlives the account.
CREATE TABLE sign_in_attempt
(
    id         BIGSERIAL    NOT NULL PRIMARY KEY,
    username   VARCHAR(200) NOT NULL,
    user_id    BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    outcome    VARCHAR(32)  NOT NULL,
    method     VARCHAR(64)  NOT NULL,
    at         TIMESTAMPTZ  NOT NULL,
    network    VARCHAR(64),
    country    VARCHAR(2),
    user_agent VARCHAR(400),
    anomalies  VARCHAR(400)
);

-- The throttle asks "how many failures for this username since a moment", and the anomaly checks
-- ask "what has this account done before" — both read by username and time, newest first.
CREATE INDEX idx_sign_in_attempt_username ON sign_in_attempt (username, at DESC);
-- The other half of the throttle, and the check for one source trying many accounts.
CREATE INDEX idx_sign_in_attempt_network ON sign_in_attempt (network, at DESC);
-- The activity list somebody reads about themselves.
CREATE INDEX idx_sign_in_attempt_user ON sign_in_attempt (user_id, at DESC);
-- The sweep that drops what is older than the retention window.
CREATE INDEX idx_sign_in_attempt_at ON sign_in_attempt (at);
