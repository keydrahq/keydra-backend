-- What an instance is holding, rather than only that it is here.
--
-- The roster said how many Keydras are running and which one does the chores. It did not say what
-- any of them was doing, which is the number that matters on the morning one pod is busy and its
-- neighbour idles, and during a rolling upgrade when somebody wants to watch one drain.
--
-- Written on the beat that renews the lease, like everything else on this row: an instance that can
-- still write here is one that can still renew, and the numbers then age exactly as fast as the row
-- they are on.

-- How many browsers this instance is talking to. Where a load balancer's decisions show up.
ALTER TABLE keydra_instance ADD COLUMN sockets INTEGER NOT NULL DEFAULT 0;

-- Connections held open against a target because somebody is looking at them: subscriptions and
-- command watches. Separate from the sockets because "has visitors" and "has visitors watching
-- something" are different facts about how expensive this instance is to restart.
ALTER TABLE keydra_instance ADD COLUMN streams INTEGER NOT NULL DEFAULT 0;

-- Long work under way: a keyspace being walked, a tunnel being held.
ALTER TABLE keydra_instance ADD COLUMN jobs INTEGER NOT NULL DEFAULT 0;

-- Which targets this instance holds clients for, as a list of ids.
--
-- One column rather than a join table. The list changes rarely and is rewritten every beat, so a
-- table would take a delete and an insert per instance per beat to hold what is usually the same
-- answer; and the question it would make cheap — which instances hold this target — is a question
-- about a handful of rows, because a fleet is three instances or ten.
--
-- No foreign key, deliberately. The row reports what this instance held a few seconds ago, and a
-- cascade that edited it to match a target deleted since would make it a worse report.
ALTER TABLE keydra_instance ADD COLUMN watching VARCHAR(4000);
