-- Telegram, Slack and WhatsApp as places an alert can go.
--
-- Three kinds, two columns. Each of the three is a token and somewhere to put the message, so they
-- share those rather than carrying six columns of which four would always be null; only WhatsApp
-- needs a third, because its Cloud API sends *from* a numbered identity as well as *to* one.
--
-- The token is encrypted at rest by the same converter as every other credential in this schema,
-- which is why it is sized like the others rather than like a word.
ALTER TABLE alert_delivery ADD COLUMN api_token VARCHAR(2000);
ALTER TABLE alert_delivery ADD COLUMN recipient VARCHAR(500);
ALTER TABLE alert_delivery ADD COLUMN sender_id VARCHAR(100);
