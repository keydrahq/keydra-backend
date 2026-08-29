-- Where Keydra's own troubles are announced.
--
-- Phase 49 made "does this answer" a fact that is checked and written down; until this existed the
-- whole of it rested on somebody having the page open. A backup destination whose credentials
-- expired is otherwise discovered at three in the morning three weeks later, which is the sentence
-- that started phase 49 and is only half answered by seeing it on a page.
--
-- The same destinations the alert rules use, so a token is rotated in one place. Not a rule: a
-- destination is reachable or it is not, and there is no threshold to pick or window to average
-- over — what varies is only where the news goes.
CREATE TABLE instance_notice_delivery (
    delivery_id BIGINT NOT NULL PRIMARY KEY REFERENCES alert_delivery (id) ON DELETE CASCADE
);
