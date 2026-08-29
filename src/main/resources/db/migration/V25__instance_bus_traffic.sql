-- What each instance has put on the notification bus and taken off it.
--
-- Cumulative counters rather than rates: a counter only goes up, so two readings a few seconds
-- apart give a rate and one reading gives nothing to disagree about. Whoever reads them does the
-- arithmetic, which is what stops this from being a second and slower clock.
--
-- On the roster row rather than in each instance's memory, because the question is about all of
-- them: an instance can only count its own, and the page shows everybody's.
ALTER TABLE keydra_instance ADD COLUMN published BIGINT NOT NULL DEFAULT 0;
ALTER TABLE keydra_instance ADD COLUMN received  BIGINT NOT NULL DEFAULT 0;
