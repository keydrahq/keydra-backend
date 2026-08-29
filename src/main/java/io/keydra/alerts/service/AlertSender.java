package io.keydra.alerts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.alerts.dto.AlertNotice;
import io.keydra.alerts.entity.AlertDelivery;
import io.keydra.alerts.entity.DeliveryOutcome;
import io.keydra.alerts.persistence.AlertDeliveryRepository;
import io.keydra.common.net.BlockedAddressException;
import io.keydra.common.net.EgressGuard;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mail.MailConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Getting an alert out of the machine.
 *
 * <p>Through the producer the backups already go through, which is the same argument phase 11 made:
 * an HTTP endpoint and a mail server are two components on the classpath rather than two clients
 * somebody wrote, and the next one somebody wants is a dependency line. Telegram and Slack are that
 * line being drawn.
 *
 * <p>WhatsApp is the exception, and it is worth saying why. Camel has a component for it, but no
 * Quarkus extension — and the Cloud API it wraps is a POST of JSON with a bearer token, which is
 * the HTTP producer already in this class. Pulling in a component built for a different runtime, to
 * make a request this one already knows how to make, buys nothing and risks the parts of Quarkus
 * that decide things at build time.
 *
 * <p>Nothing here ever fails its caller. A delivery that does not arrive is a fact recorded on the
 * event, not a reason for the alert to be lost — the event is already written and already on the
 * notification hub by the time this runs, and an exception thrown back up would only endanger the
 * copies that did work.
 */
@ApplicationScoped
public class AlertSender {

    private static final Logger LOG = Logger.getLogger(AlertSender.class);

    /**
     * How long to wait for the far end.
     *
     * <p>Bounded so an event cannot sit in "sending" forever because somebody's chat tool is having
     * a bad afternoon. The request itself may still be in flight when this gives up; what the
     * timeout guarantees is an answer for the history, not that the far end stopped.
     */
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** What became of one attempt: where it went, how it went, and what to show about it. */
    public record Sent(String name, DeliveryOutcome outcome, String detail) {}

    private final AlertDeliveryRepository deliveries;
    private final CamelContext camel;
    private final EgressGuard egress;
    private final ProducerTemplate producer;
    private final ObjectMapper json;
    private final Vertx vertx;
    private final String whatsAppBase;
    private final String whatsAppApiVersion;

    @Inject
    AlertSender(
            AlertDeliveryRepository deliveries,
            CamelContext camel,
            EgressGuard egress,
            ProducerTemplate producer,
            ObjectMapper json,
            Vertx vertx,
            @ConfigProperty(
                            name = "keydra.alerts.whatsapp.base-uri",
                            defaultValue = "https://graph.facebook.com")
                    String whatsAppBase,
            @ConfigProperty(name = "keydra.alerts.whatsapp.api-version", defaultValue = "v21.0")
                    String whatsAppApiVersion) {
        this.deliveries = deliveries;
        this.camel = camel;
        this.egress = egress;
        this.producer = producer;
        this.json = json;
        this.vertx = vertx;
        this.whatsAppBase = whatsAppBase;
        this.whatsAppApiVersion = whatsAppApiVersion;
    }

    /**
     * Sends a notice to one configured delivery, whatever state that delivery turns out to be in.
     */
    public Uni<Sent> send(Long deliveryId, AlertNotice notice) {
        return deliveries
                .forUse(deliveryId)
                .flatMap(
                        delivery -> {
                            if (delivery == null) {
                                return Uni.createFrom()
                                        .item(
                                                new Sent(
                                                        null,
                                                        DeliveryOutcome.FAILED,
                                                        "The delivery this rule pointed at no"
                                                                + " longer exists"));
                            }
                            if (!delivery.enabled) {
                                return Uni.createFrom()
                                        .item(
                                                new Sent(
                                                        delivery.name,
                                                        DeliveryOutcome.FAILED,
                                                        delivery.name + " is turned off"));
                            }
                            return deliver(delivery, notice);
                        })
                .onFailure()
                .recoverWithItem(
                        failure -> new Sent(null, DeliveryOutcome.FAILED, plainest(failure)));
    }

    /**
     * Sends a notice to a delivery already in hand.
     *
     * <p>The one path, used by the rules and by the "does this work" button alike. A test that went
     * a different way would be a test of a different thing.
     */
    public Uni<Sent> deliver(AlertDelivery delivery, AlertNotice notice) {
        Uni<Sent> attempt =
                switch (delivery.kind) {
                    case WEBHOOK -> post(delivery, notice);
                    case EMAIL -> mail(delivery, notice);
                    case TELEGRAM -> telegram(delivery, notice);
                    case SLACK -> slack(delivery, notice);
                    case WHATSAPP -> whatsApp(delivery, notice);
                };
        return attempt.ifNoItem()
                .after(PATIENCE)
                .failWith(
                        () ->
                                new IllegalStateException(
                                        delivery.name
                                                + " did not answer within "
                                                + PATIENCE.toSeconds()
                                                + " seconds"))
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not send an alert to %s", delivery.name);
                            return new Sent(
                                    delivery.name, DeliveryOutcome.FAILED, plainest(failure));
                        });
    }

    // --- The two kinds -----------------------------------------------------

    private Uni<Sent> post(AlertDelivery delivery, AlertNotice notice) {
        if (delivery.url == null || delivery.url.isBlank()) {
            return Uni.createFrom()
                    .item(
                            new Sent(
                                    delivery.name,
                                    DeliveryOutcome.FAILED,
                                    "This delivery has no address to post to"));
        }
        // Checked again here and not only when the delivery was saved. A row can predate this
        // check, and a name can resolve to something else than it did then — the address a
        // request is actually made to is the one worth asking about.
        try {
            egress.checkBlocking(delivery.url);
        } catch (BlockedAddressException blocked) {
            return Uni.createFrom()
                    .item(new Sent(delivery.name, DeliveryOutcome.FAILED, blocked.getMessage()));
        }
        String endpoint = withoutQuery(delivery.url);
        String query = queryOf(delivery.url);
        String body = body(notice);

        return offEventLoop(
                () -> {
                    Map<String, Object> headers = new LinkedHashMap<>();
                    headers.put(Exchange.HTTP_METHOD, "POST");
                    headers.put(Exchange.CONTENT_TYPE, "application/json");
                    // The query string travels as a header rather than in the endpoint URI:
                    // Camel reads an endpoint's query as its own options, and a webhook address
                    // that carries a token in one would be read as a component setting and
                    // refused.
                    if (query != null) {
                        headers.put(Exchange.HTTP_QUERY, query);
                    }
                    if (isSet(delivery.headerName) && isSet(delivery.headerValue)) {
                        headers.put(delivery.headerName, delivery.headerValue);
                    }

                    Exchange answer =
                            producer.request(
                                    endpoint,
                                    exchange -> {
                                        exchange.getIn().setBody(body);
                                        exchange.getIn().setHeaders(headers);
                                    });
                    if (answer.getException() != null) {
                        throw new IllegalStateException(plainest(answer.getException()));
                    }
                    Integer code =
                            answer.getMessage()
                                    .getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
                    return new Sent(
                            delivery.name,
                            DeliveryOutcome.SENT,
                            code == null ? "Accepted" : "HTTP " + code);
                });
    }

    private Uni<Sent> mail(AlertDelivery delivery, AlertNotice notice) {
        if (!isSet(delivery.smtpHost) || !isSet(delivery.toAddresses)) {
            return Uni.createFrom()
                    .item(
                            new Sent(
                                    delivery.name,
                                    DeliveryOutcome.FAILED,
                                    "This delivery needs a mail server and an address to send"
                                            + " to"));
        }
        String endpoint = mailUri(delivery);
        String subject = AlertWording.subject(notice);
        String text = AlertWording.body(notice);

        return offEventLoop(
                () -> {
                    Map<String, Object> headers = new LinkedHashMap<>();
                    headers.put(MailConstants.MAIL_SUBJECT, subject);
                    producer.sendBodyAndHeaders(endpoint, text, headers);
                    return new Sent(
                            delivery.name, DeliveryOutcome.SENT, "Sent to " + delivery.toAddresses);
                });
    }

    /**
     * A Telegram bot, posting into one chat.
     *
     * <p>Both values go through {@code RAW()}: a bot token contains a colon by construction, and a
     * group chat id begins with a minus sign — the two characters most likely to be read as URI
     * syntax rather than as a value.
     */
    private Uni<Sent> telegram(AlertDelivery delivery, AlertNotice notice) {
        String endpoint =
                "telegram:bots?authorizationToken=RAW("
                        + delivery.apiToken
                        + ")&chatId=RAW("
                        + delivery.recipient.trim()
                        + ")";
        String text = AlertWording.sentence(notice);

        return offEventLoop(
                () -> {
                    producer.sendBody(endpoint, text);
                    return new Sent(
                            delivery.name,
                            DeliveryOutcome.SENT,
                            "Sent to chat " + delivery.recipient.trim());
                });
    }

    /**
     * A Slack bot, posting into one channel by name.
     *
     * <p>The other way into Slack. Its incoming webhooks are a URL and the webhook kind already
     * posts to those; this one carries a bot token, which means the channel is a name that can be
     * changed rather than a new address that has to be issued.
     */
    private Uni<Sent> slack(AlertDelivery delivery, AlertNotice notice) {
        String endpoint =
                "slack:" + channelOf(delivery.recipient) + "?token=RAW(" + delivery.apiToken + ")";
        String text = AlertWording.sentence(notice);

        return offEventLoop(
                () -> {
                    producer.sendBody(endpoint, text);
                    return new Sent(
                            delivery.name,
                            DeliveryOutcome.SENT,
                            "Sent to " + channelOf(delivery.recipient));
                });
    }

    /**
     * WhatsApp, through the Cloud API.
     *
     * <p>The same HTTP producer the webhooks use, because that is all the Cloud API is: a POST to a
     * numbered identity's messages endpoint, carrying a bearer token and a small piece of JSON. The
     * API version is configurable because Meta retires them on a schedule of its own, and a
     * hard-coded one is a delivery that stops working on a date nobody wrote down.
     */
    private Uni<Sent> whatsApp(AlertDelivery delivery, AlertNotice notice) {
        String endpoint =
                whatsAppBase + "/" + whatsAppApiVersion + "/" + delivery.senderId + "/messages";
        List<String> numbers = recipientsOf(delivery.recipient);
        String sentence = AlertWording.sentence(notice);

        return offEventLoop(
                () -> {
                    Map<String, Object> headers = new LinkedHashMap<>();
                    headers.put(Exchange.HTTP_METHOD, "POST");
                    headers.put(Exchange.CONTENT_TYPE, "application/json");
                    headers.put("Authorization", "Bearer " + delivery.apiToken);

                    /*
                     * One request per number, because the Cloud API takes one. Mail is the
                     * exception rather than the rule here: SMTP was built to address several
                     * people at once and almost nothing since has been.
                     *
                     * Every number is tried even after one fails, and the first failure is what
                     * is reported. A delivery to four people that stopped at the first is three
                     * people who were not told, and the whole point of naming four was that all
                     * four should hear.
                     */
                    String firstFailure = null;
                    int sent = 0;
                    for (String to : numbers) {
                        Exchange answer =
                                producer.request(
                                        endpoint,
                                        exchange -> {
                                            exchange.getIn().setBody(whatsAppBody(to, sentence));
                                            exchange.getIn().setHeaders(headers);
                                        });
                        if (answer.getException() != null) {
                            if (firstFailure == null) {
                                firstFailure = plainest(answer.getException());
                            }
                            continue;
                        }
                        sent++;
                    }
                    if (sent == 0) {
                        throw new IllegalStateException(
                                firstFailure == null ? "Nobody to send to" : firstFailure);
                    }
                    return new Sent(
                            delivery.name,
                            DeliveryOutcome.SENT,
                            firstFailure == null
                                    ? "Sent to " + String.join(", ", numbers)
                                    : "Sent to "
                                            + sent
                                            + " of "
                                            + numbers.size()
                                            + "; the first refusal was: "
                                            + firstFailure);
                });
    }

    /**
     * The people a message is for, however somebody wrote them down.
     *
     * <p>Comma separated, the way the mail field already is and the way anybody writes a list of
     * addresses without being told a format. Blanks are dropped rather than sent, because a
     * trailing comma is a typing habit and not a recipient.
     */
    static List<String> recipientsOf(String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(written.split("[,;]"))
                .map(String::trim)
                .filter(one -> !one.isEmpty())
                .toList();
    }

    /** The message the Cloud API expects: who it is for, and one line of text. */
    private String whatsAppBody(String to, String sentence) {
        Map<String, Object> text = new LinkedHashMap<>();
        // Off deliberately: a preview would fetch whatever a target name happened to look
        // like a link to, from Meta's servers rather than from here.
        text.put("preview_url", false);
        text.put("body", sentence);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);
        payload.put("type", "text");
        payload.put("text", text);
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            LOG.warn("Could not write a WhatsApp message as JSON", impossible);
            return "{\"messaging_product\":\"whatsapp\",\"to\":\""
                    + to
                    + "\",\"type\":\"text\",\"text\":{\"body\":\""
                    + sentence.replace("\"", "'")
                    + "\"}}";
        }
    }

    /** A channel as Slack writes it, whether or not somebody typed the hash. */
    static String channelOf(String recipient) {
        String channel = recipient.trim();
        return channel.startsWith("#") || channel.startsWith("@") ? channel : "#" + channel;
    }

    /**
     * The mail endpoint for a delivery.
     *
     * <p>Everything that could contain a character a URI reserves goes through {@code RAW()}, which
     * is Camel's way of saying "this value is not encoded" — an address and a password are exactly
     * the two fields where somebody's {@code +} or {@code @} would otherwise be read as syntax.
     */
    private static String mailUri(AlertDelivery delivery) {
        int port = delivery.smtpPort == null ? 25 : delivery.smtpPort;
        StringBuilder uri =
                new StringBuilder("smtp://").append(delivery.smtpHost).append(':').append(port);
        uri.append("?to=RAW(").append(delivery.toAddresses.trim()).append(')');
        if (isSet(delivery.fromAddress)) {
            uri.append("&from=RAW(").append(delivery.fromAddress.trim()).append(')');
        }
        if (isSet(delivery.username)) {
            uri.append("&username=RAW(").append(delivery.username).append(')');
        }
        if (isSet(delivery.password)) {
            uri.append("&password=RAW(").append(delivery.password).append(')');
        }
        // STARTTLS rather than a TLS port: what almost every mail server offers on 587, and
        // what the flag on the row is asking for.
        uri.append("&mail.smtp.starttls.enable=").append(delivery.smtpTls);
        return uri.toString();
    }

    /**
     * The JSON a webhook receives.
     *
     * <p>Both shapes at once, on purpose. {@code text} is what Slack and Teams render, {@code
     * content} is what Discord renders, and the named fields are what anything somebody wrote
     * themselves will want — a body that only had one of the three would work with one service and
     * arrive as an empty message in the others.
     */
    private String body(AlertNotice notice) {
        String sentence = AlertWording.sentence(notice);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", sentence);
        payload.put("content", sentence);
        payload.put("state", notice.kind());
        payload.put("rule", notice.ruleName());
        payload.put("ruleId", notice.ruleId());
        payload.put("connection", notice.connectionName());
        payload.put("connectionId", notice.connectionId());
        payload.put("metric", notice.metric());
        payload.put("comparison", notice.comparison());
        payload.put("reading", notice.reading());
        payload.put("threshold", notice.threshold());
        payload.put("at", notice.at().toString());
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException impossible) {
            // Every value here is a string, a number or an enum; this cannot happen, and a
            // sentence is still worth more than a failure if it somehow does.
            LOG.warn("Could not write an alert as JSON", impossible);
            return "{\"text\":\"" + sentence.replace("\"", "'") + "\"}";
        }
    }

    /**
     * Forgets the cached endpoints, so the next message is built from what is stored now.
     *
     * <p>The same rule the backup destinations follow, and for the same reason: Camel caches an
     * endpoint by its URI, a mail URI carries the password, and an edited password would otherwise
     * keep sending with the old one until a restart.
     */
    public void forget() {
        try {
            camel.removeEndpoints("*");
        } catch (Exception stubborn) {
            // Nothing worth failing a save over: an endpoint that will not close is replaced
            // by the next use either way.
            LOG.debug("Could not clear the cached endpoints", stubborn);
        }
    }

    // --- Plumbing ----------------------------------------------------------

    private <T> Uni<T> offEventLoop(Supplier<T> work) {
        return Uni.createFrom()
                .completionStage(() -> vertx.executeBlocking(work::get, false).toCompletionStage());
    }

    private static String withoutQuery(String url) {
        int mark = url.indexOf('?');
        return mark < 0 ? url : url.substring(0, mark);
    }

    private static String queryOf(String url) {
        int mark = url.indexOf('?');
        return mark < 0 || mark == url.length() - 1 ? null : url.substring(mark + 1);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * The sentence worth showing out of a chain of wrappers.
     *
     * <p>Camel reports a refused webhook as an execution exception around the real one, and the
     * useful half is the innermost message: "401 Unauthorized" rather than "Failed to invoke the
     * endpoint".
     */
    static String plainest(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        String text =
                message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** Whether a string is a URL this can post to, asked before a delivery is stored. */
    static boolean isPostable(String url) {
        try {
            URI parsed = URI.create(url);
            String scheme = parsed.getScheme();
            return parsed.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException notAUrl) {
            return false;
        }
    }
}
