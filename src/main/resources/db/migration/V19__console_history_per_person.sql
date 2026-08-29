-- Command history belongs to whoever typed it.
--
-- Before this it belonged to the target: one list per server, shared by everybody who could reach
-- a console on it. Two things were wrong with that and only one of them is privacy. Anybody with
-- CONSOLE_RUN could read what every other operator and administrator had typed against that server
-- — command lines carry key names, patterns and arguments — and, worse, could delete all of it.
-- The most powerful thing the product does was the thing whose record the person doing it could
-- erase.
--
-- So the rows get an owner. A history is a person's own up arrow and nothing else; anybody wanting
-- to know what has been run against a target reads the audit log, which is written by a different
-- mechanism and is not somebody's to clear.
--
-- Existing rows have no owner and cannot be given one — nothing recorded who typed them. They go.
-- A command history is a convenience with no value beyond the session it belongs to, and keeping
-- rows readable by everybody in a table that now promises otherwise would be keeping exactly the
-- problem this removes.
DELETE FROM command_history;

ALTER TABLE command_history
    ADD COLUMN user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;

-- Every read is "this person, on this target, most recent first".
DROP INDEX IF EXISTS idx_command_history_connection;
CREATE INDEX idx_command_history_owner ON command_history (user_id, connection_id, id DESC);
