-- Rules that compare with the past.
--
-- Every rule until now was a number somebody guessed: "above eight hundred megabytes" is a
-- sentence about a server whose ordinary Tuesday the writer had to already know, and the
-- number is wrong twice — too low on the day traffic doubles for a good reason, and too high
-- on the night something is quietly leaking.
--
-- A baseline rule says what people mean instead: busier than usual. The threshold becomes a
-- percentage of what the metric read over an earlier window, and the window is described by
-- how wide it is and how far back it sits — an hour, seven days ago, being "the same hour
-- last week".
--
-- Existing rules become ABSOLUTE, which is what they already were.

ALTER TABLE alert_rule ADD COLUMN basis VARCHAR(16) NOT NULL DEFAULT 'ABSOLUTE';
ALTER TABLE alert_rule ADD COLUMN baseline_window_seconds INTEGER NOT NULL DEFAULT 3600;
ALTER TABLE alert_rule ADD COLUMN baseline_offset_seconds INTEGER NOT NULL DEFAULT 0;
