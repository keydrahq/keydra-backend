-- Phase 61: the instance that stopped, and the work nobody is doing.
--
-- Two facts about Keydra itself that until now existed only on a page somebody had to have open. An
-- instance that died is a row that quietly stops moving; a fleet with nobody doing the chores is a
-- schedule that does not fire and a sweep that does not run, none of which announces itself because
-- announcing is one of the things that stopped.

-- Whether somebody has already said this instance stopped answering.
--
-- On the row rather than in a table of its own: it is a fact about this instance, it is written by
-- whichever instance notices, and the row is already kept for a hundred and twenty leases before it
-- is forgotten. Cleared again if the same name starts beating — which only happens where the id was
-- configured, because a name Keydra makes up carries something random after it.
--
-- The update that sets it is what decides who speaks. Three instances noticing the same death send
-- one message between them, because two of the three change no row — and none of them has to be in
-- charge, which is the point: the condition being watched for is that nobody is in charge.
ALTER TABLE keydra_instance ADD COLUMN absence_announced BOOLEAN NOT NULL DEFAULT FALSE;

-- What has already been said about Keydra's own condition.
--
-- One row per subject, and there is exactly one subject: whether anybody is doing the chores. A
-- table rather than a column somewhere because the thing it is about is the absence of a row —
-- there is nowhere else to hang it.
--
-- Read and written by every instance rather than by the leader, for the reason above.
CREATE TABLE instance_notice_state
(
    subject VARCHAR(64) NOT NULL,
    -- Whether the bad news has been sent and the good news has not.
    firing  BOOLEAN     NOT NULL,
    since   TIMESTAMP   NOT NULL,
    CONSTRAINT instance_notice_state_pkey PRIMARY KEY (subject)
);
