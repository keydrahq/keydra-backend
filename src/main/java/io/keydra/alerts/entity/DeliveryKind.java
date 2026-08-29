package io.keydra.alerts.entity;

/**
 * How an alert leaves the machine.
 *
 * <p>Five, and they divide into two groups. A webhook and mail are the general ones: between them
 * they reach anything that accepts an HTTP request and anybody who reads an inbox, which is most of
 * the world. The other three are named because being named is what makes them usable — somebody
 * whose team lives in Telegram should be able to say "Telegram" and paste a token, rather than work
 * out which URL the bot API wants and what shape the body has to be.
 *
 * <p>Slack appears in both groups on purpose. Its incoming webhooks are a URL, which the webhook
 * kind already posts to; the kind below is the other way in, where a bot token and a channel name
 * mean the channel can be changed without issuing a new address.
 */
public enum DeliveryKind {
    /** An HTTP request with the alert as JSON — every incoming webhook there is. */
    WEBHOOK,
    /** Mail, through a server somebody nominates. */
    EMAIL,
    /** A Telegram bot, posting into one chat. */
    TELEGRAM,
    /** A Slack bot, posting into one channel by name. */
    SLACK,
    /** WhatsApp, through the Cloud API and a number registered with it. */
    WHATSAPP
}
