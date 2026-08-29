-- A migration script can turn a key down, and that is its own outcome.
--
-- Not folded into `skipped`, which already means something else: a key the target already had was
-- left alone, where a key a script turned down was never offered to it. Nor left to fall into the
-- gap between `scanned` and the counted outcomes — the dialog explains that gap as keys that
-- expired while the walk was running, and it would then be explaining these away as something they
-- are not.
--
-- Zero for every row written before scripts existed, which is true of all of them.
ALTER TABLE key_migration ADD COLUMN dropped BIGINT NOT NULL DEFAULT 0;
