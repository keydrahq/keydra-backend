-- What this installation asks of everybody who signs in.
--
-- One row, and it stays one row: the primary key is a constant so a second one cannot be written.
-- Its own table with named columns rather than a general instance_setting(name, value), because a
-- settings table is where everything ends up and everything that ends up there arrives without the
-- guard that should have come with it. The guard on this one — that nobody may require a factor
-- they do not have — lives in the service, and it only means anything while there is one thing here
-- to guard.
--
-- No row is a valid state and means "nothing is required". That matters because dev builds the
-- schema from the entities rather than from these files: a policy that only exists once a migration
-- has seeded it would be a policy that is absent in development and present nowhere else.
CREATE TABLE sign_in_policy (
    id                     SMALLINT     NOT NULL PRIMARY KEY,
    second_factor_required BOOLEAN      NOT NULL DEFAULT FALSE,
    changed_at             TIMESTAMPTZ,
    -- Who last changed it. A name rather than an account id: the audit log holds the event, and
    -- this is the line the page shows beside the switch, which has to survive the account being
    -- deleted.
    changed_by             VARCHAR(200),
    CONSTRAINT sign_in_policy_is_one_row CHECK (id = 1)
);
