package io.keydra.alerts.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Somewhere an alert is sent.
 *
 * <p>A row like a backup destination is, for the same reason and with the same rules: it carries a
 * credential to somewhere outside Keydra, so the credential is encrypted at rest and the API will
 * only ever say whether one exists. Shared between rules, so a token is rotated in one place rather
 * than in every rule that uses it.
 *
 * <p>The columns are per kind because the two kinds have nothing in common beyond a name — an
 * address is not a URL and a mail server is not a header — and a table that pretended otherwise
 * would need explaining every time somebody read it.
 */
@Entity
@Table(
        name = "alert_delivery",
        indexes = {@Index(name = "idx_alert_delivery_name", columnList = "name", unique = true)})
public class AlertDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "alert_delivery_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public DeliveryKind kind = DeliveryKind.WEBHOOK;

    @Column(nullable = false)
    public boolean enabled = true;

    // --- A webhook ---------------------------------------------------------

    /**
     * Where to post.
     *
     * <p>Encrypted, and never returned by the API — which looks like paranoia about a URL until you
     * look at one. A Slack or Discord webhook address carries its authorisation in its path:
     * anybody holding the string can post into that channel as this application, forever, and no
     * password is involved anywhere. So it is a credential, and it is treated as one: kept
     * encrypted at rest, shown only as its host, and edited by the same keep-or-clear rule as every
     * other secret here.
     */
    @Column(length = 2000)
    @Convert(converter = EncryptedStringConverter.class)
    public String url;

    /**
     * The host it posts to, kept in the clear so a list can say where alerts go.
     *
     * <p>Derived from the address when it is saved rather than by taking it apart on the way out:
     * this is the part that is not a secret, and separating them once is better than trusting every
     * future caller to redact.
     */
    @Column(name = "url_host", length = 255)
    public String urlHost;

    /**
     * A header to send with it, for the services that authenticate with one.
     *
     * <p>The name is not a secret and the value is. Split into two columns rather than one line of
     * "Name: value" so the value can be encrypted and the name can be shown.
     */
    @Column(name = "header_name", length = 200)
    public String headerName;

    @Column(name = "header_value", length = 2000)
    @Convert(converter = EncryptedStringConverter.class)
    public String headerValue;

    // --- Mail --------------------------------------------------------------

    @Column(name = "smtp_host", length = 255)
    public String smtpHost;

    @Column(name = "smtp_port")
    public Integer smtpPort;

    /** Whether to start TLS on the connection, which is what almost every mail server wants. */
    @Column(name = "smtp_tls", nullable = false)
    public boolean smtpTls = true;

    @Column(length = 200)
    public String username;

    @Column(length = 1000)
    @Convert(converter = EncryptedStringConverter.class)
    public String password;

    @Column(name = "from_address", length = 320)
    public String fromAddress;

    /** Who it goes to, comma separated — one field because that is how people write it. */
    @Column(name = "to_addresses", length = 2000)
    public String toAddresses;

    // --- Telegram, Slack and WhatsApp -------------------------------------

    /**
     * The token that proves who is speaking: a Telegram bot's, a Slack bot's, or a WhatsApp access
     * token.
     *
     * <p>Encrypted and never returned, like every other credential here. Each of these is enough on
     * its own to post as this application for as long as nobody revokes it.
     */
    @Column(name = "api_token", length = 2000)
    @Convert(converter = EncryptedStringConverter.class)
    public String apiToken;

    /**
     * Where the message goes: a Telegram chat id, a Slack channel, one or more WhatsApp numbers.
     *
     * <p>Comma separated where the channel takes more than one, which is WhatsApp — the same shape
     * {@link #toAddresses} uses, and for the same reason: that is how anybody writes a list of
     * people without being told a format. Telegram and Slack each address a room rather than a
     * person, so naming several would be naming several rooms, which is what several deliveries are
     * for.
     *
     * <p>Not a secret in any of them — a channel name is a channel name — so it is kept in the
     * clear and a list can say where a delivery points without decrypting anything.
     */
    @Column(length = 500)
    public String recipient;

    /**
     * WhatsApp only: the phone number id the message is sent *from*.
     *
     * <p>A Cloud API account can hold several numbers and the id names which one is speaking. It is
     * part of the address the request is made to rather than something about the recipient, which
     * is why it is its own column instead of being packed into one.
     */
    @Column(name = "sender_id", length = 100)
    public String senderId;
}
