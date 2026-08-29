package io.keydra.mail.service;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.mail.MailConstants;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The mail Keydra sends about itself.
 *
 * <p>Distinct from the mail an alert sends, and the difference is who chose the server. An alert
 * delivery is a row somebody filled in, one per destination, because different rules go to
 * different teams. This is the instance's own relay: one address, configured once, used by anything
 * Keydra needs to tell a person directly — invitations first, and whatever comes after them.
 *
 * <p>Through the same Camel producer as the alerts, so an SMTP relay is four settings rather than a
 * client. Resend, Postmark, SES and a company's own server are the same four.
 *
 * <p>Sending can fail and the caller has to be able to carry on. An invitation whose mail bounced
 * is still an invitation — the link exists, and handing it to the administrator who asked is worse
 * than mail and much better than refusing to make accounts.
 */
@ApplicationScoped
public class Mailer {

    private static final Logger LOG = Logger.getLogger(Mailer.class);

    /** How long to wait for a relay before giving up on it. */
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private final CamelContext camel;
    private final ProducerTemplate producer;
    private final Vertx vertx;
    private final Optional<String> host;
    private final int port;
    private final boolean tls;
    private final Optional<String> username;
    private final Optional<String> password;
    private final Optional<String> from;

    @Inject
    Mailer(
            CamelContext camel,
            ProducerTemplate producer,
            Vertx vertx,
            @ConfigProperty(name = "keydra.mail.host") Optional<String> host,
            @ConfigProperty(name = "keydra.mail.port", defaultValue = "587") int port,
            @ConfigProperty(name = "keydra.mail.tls", defaultValue = "true") boolean tls,
            @ConfigProperty(name = "keydra.mail.username") Optional<String> username,
            @ConfigProperty(name = "keydra.mail.password") Optional<String> password,
            @ConfigProperty(name = "keydra.mail.from") Optional<String> from) {
        this.camel = camel;
        this.producer = producer;
        this.vertx = vertx;
        this.host = host;
        this.port = port;
        this.tls = tls;
        this.username = username;
        this.password = password;
        this.from = from;
    }

    /**
     * Whether this instance can send mail at all.
     *
     * <p>Asked before anything relies on it, so a feature that needs to reach somebody can offer
     * the other way round instead of failing. Both a server and an address it may send from: a
     * relay that accepts the connection and refuses the sender is a configuration that looks
     * complete and sends nothing.
     */
    public boolean canSend() {
        return isSet(host) && isSet(from);
    }

    /** Where mail appears to come from, for the pages that say so. */
    public Optional<String> fromAddress() {
        return from;
    }

    /**
     * Sends one letter, and answers whether it went.
     *
     * <p>Never fails its caller. What the caller does about a message that did not go is the
     * caller's decision, and for an invitation that decision is "show the administrator the link".
     *
     * <p>Both bodies go, as {@code multipart/alternative}. Camel builds that from the alternative
     * body header plus a content type, and it puts the alternative part first — which is the order
     * MIME asks for, least-rich before richest, so a client that reads the first part it
     * understands reads the plain one. The type is set as {@code Content-Type} rather than through
     * Camel's own {@code contentType} header because that one is not filtered out of the headers
     * that reach the message, and would arrive as a second, misspelt header of its own.
     *
     * <p>The charset in it is also what the subject is encoded with, which is why a subject in
     * Turkish survives the trip. That is the opposite of the decision the alerts made: an alert
     * subject is deliberately ASCII because it ends up in filters and phone notifications written
     * by things that are not mail clients, and this one ends up in somebody's inbox.
     */
    public Uni<Boolean> send(String to, Letter letter) {
        if (!canSend()) {
            return Uni.createFrom().item(false);
        }
        String endpoint = endpoint(to);
        return offEventLoop(
                        () -> {
                            Map<String, Object> headers = new LinkedHashMap<>();
                            headers.put(MailConstants.MAIL_SUBJECT, letter.subject());
                            headers.put("Content-Type", "text/html; charset=UTF-8");
                            headers.put(MailConstants.MAIL_ALTERNATIVE_BODY, letter.text());
                            producer.sendBodyAndHeaders(endpoint, letter.html(), headers);
                            return true;
                        })
                .ifNoItem()
                .after(PATIENCE)
                .recoverWithItem(false)
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            // The address is not logged with the failure: who was written to is
                            // not something a log needs, and the relay's complaint is.
                            LOG.warnf("Could not send mail: %s", plainest(failure));
                            return false;
                        });
    }

    /**
     * The endpoint for one message.
     *
     * <p>Everything that could contain a character a URI reserves goes through {@code RAW()}, which
     * is Camel's way of saying "this value is not encoded" — an address and a password are exactly
     * the two fields where somebody's {@code +} or {@code @} would be read as syntax.
     */
    private String endpoint(String to) {
        StringBuilder uri =
                new StringBuilder("smtp://").append(host.orElseThrow()).append(':').append(port);
        uri.append("?to=RAW(").append(to.trim()).append(')');
        uri.append("&from=RAW(").append(from.orElseThrow().trim()).append(')');
        if (isSet(username)) {
            uri.append("&username=RAW(").append(username.get()).append(')');
        }
        if (isSet(password)) {
            uri.append("&password=RAW(").append(password.get()).append(')');
        }
        uri.append("&mail.smtp.starttls.enable=").append(tls);
        return uri.toString();
    }

    /**
     * Forgets the cached endpoints, so the next message is built from what is configured now.
     *
     * <p>The rule the alert deliveries already follow: Camel caches an endpoint by its URI, and a
     * mail URI carries the password.
     */
    public void forget() {
        try {
            camel.removeEndpoints("smtp://*");
        } catch (Exception stubborn) {
            LOG.debug("Could not clear the cached mail endpoints", stubborn);
        }
    }

    private <T> Uni<T> offEventLoop(Supplier<T> work) {
        return Uni.createFrom()
                .completionStage(() -> vertx.executeBlocking(work::get, false).toCompletionStage());
    }

    private static boolean isSet(Optional<String> value) {
        return value.isPresent() && !value.get().isBlank();
    }

    /** The innermost message, which is the useful half of what Camel wraps a refusal in. */
    private static String plainest(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
    }
}
