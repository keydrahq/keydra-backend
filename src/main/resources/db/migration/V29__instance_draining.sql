-- Whether somebody has asked this instance to stop taking new work.
--
-- The one column on this table written from outside rather than by the instance it describes: two
-- Keydras do not connect to each other, so the request arrives wherever the balancer sent it and
-- reaches its subject through the row they both read.
--
-- Default false, and cleared again by the first announcement a process makes. A drain applies to a
-- running process, not to a name that comes back.
ALTER TABLE keydra_instance ADD COLUMN draining BOOLEAN NOT NULL DEFAULT FALSE;
