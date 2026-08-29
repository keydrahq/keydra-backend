-- What one person prefers, kept with their account rather than with their browser.
--
-- A row per preference rather than one JSON column: the shape here is genuinely name-and-value, a
-- page can write the one it changed without rewriting the rest, and a preference added next year
-- needs no migration. Nothing in the database knows what any of them mean — that is the
-- interface's business, and a server that validated them would be a second place to change every
-- time it grew a switch.
CREATE TABLE user_preference (
    user_id BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name    VARCHAR(64) NOT NULL,
    -- Bounded so a preference cannot become a place to keep a file.
    value   VARCHAR(4096) NOT NULL,
    PRIMARY KEY (user_id, name)
);
