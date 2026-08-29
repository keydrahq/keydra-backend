-- Which server a profile expects to find, as opposed to which protocol it speaks.
--
-- Existing profiles get UNKNOWN rather than a guess: they were saved before anyone was
-- asked, and the catalog already draws them from what their target reported.
ALTER TABLE connection_profile
    ADD COLUMN flavor VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
