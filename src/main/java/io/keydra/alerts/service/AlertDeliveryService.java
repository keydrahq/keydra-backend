package io.keydra.alerts.service;

import io.keydra.alerts.dto.AlertDtos.AlertDeliveryCheck;
import io.keydra.alerts.dto.AlertDtos.AlertDeliveryRequest;
import io.keydra.alerts.dto.AlertDtos.AlertDeliverySummary;
import io.keydra.alerts.dto.AlertNotice;
import io.keydra.alerts.entity.AlertDelivery;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.entity.Comparison;
import io.keydra.alerts.entity.DeliveryOutcome;
import io.keydra.alerts.entity.EventKind;
import io.keydra.alerts.exception.AlertRefusedException;
import io.keydra.alerts.mapper.AlertDeliveryMapper;
import io.keydra.alerts.persistence.AlertDeliveryRepository;
import io.keydra.alerts.persistence.AlertRuleRepository;
import io.keydra.common.net.BlockedAddressException;
import io.keydra.common.net.EgressGuard;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * The places alerts are sent, as rows.
 *
 * <p>An administrator's job and deliberately not an operator's, for the reason the backup
 * destinations give: this carries a credential to somewhere outside Keydra, and somebody who may
 * write a rule about one server is not thereby somebody who decides that its alerts go to a channel
 * of their choosing.
 */
@ApplicationScoped
public class AlertDeliveryService {

    private final AlertDeliveryRepository repository;
    private final AlertRuleRepository rules;
    private final AlertDeliveryMapper mapper;
    private final AlertSender sender;
    private final EgressGuard egress;

    @Inject
    AlertDeliveryService(
            AlertDeliveryRepository repository,
            AlertRuleRepository rules,
            AlertDeliveryMapper mapper,
            AlertSender sender,
            EgressGuard egress) {
        this.repository = repository;
        this.rules = rules;
        this.mapper = mapper;
        this.sender = sender;
        this.egress = egress;
    }

    @WithSession
    public Uni<List<AlertDeliverySummary>> list() {
        return repository
                .all()
                .flatMap(
                        found -> {
                            Uni<List<AlertDeliverySummary>> described =
                                    Uni.createFrom().item(List.of());
                            for (AlertDelivery delivery : found) {
                                described =
                                        described.flatMap(
                                                so_far ->
                                                        rules.usingDelivery(delivery.id)
                                                                .map(
                                                                        used ->
                                                                                append(
                                                                                        so_far,
                                                                                        mapper
                                                                                                .toSummary(
                                                                                                        delivery,
                                                                                                        used
                                                                                                                .intValue()))));
                            }
                            return described;
                        });
    }

    @WithTransaction
    public Uni<AlertDeliverySummary> create(AlertDeliveryRequest request) {
        return repository
                .byName(request.name())
                .flatMap(
                        existing -> {
                            if (existing != null) {
                                return refuse("A delivery called " + request.name() + " exists");
                            }
                            AlertDelivery delivery = new AlertDelivery();
                            mapper.apply(request, delivery);
                            return validate(delivery)
                                    .flatMap(ignored -> repository.save(delivery))
                                    .map(saved -> mapper.toSummary(saved, 0));
                        });
    }

    @WithTransaction
    public Uni<AlertDeliverySummary> update(Long id, AlertDeliveryRequest request) {
        return repository
                .byId(id)
                .flatMap(
                        delivery -> {
                            if (delivery == null) {
                                return refuse("No such delivery");
                            }
                            mapper.apply(request, delivery);
                            return validate(delivery)
                                    // The cached endpoint carries the mail password, so a saved
                                    // change has to take the old one out of use.
                                    .invoke(sender::forget)
                                    .flatMap(ignored -> rules.usingDelivery(id))
                                    .map(used -> mapper.toSummary(delivery, used.intValue()));
                        });
    }

    /**
     * Removes a delivery, unless something is pointing at it.
     *
     * <p>Refused rather than cascaded: a rule whose delivery quietly became null is a rule that
     * still looks configured and no longer reaches anybody, which is the exact failure this whole
     * phase exists to prevent.
     */
    @WithTransaction
    public Uni<Boolean> delete(Long id) {
        return rules.usingDelivery(id)
                .flatMap(
                        used ->
                                used > 0
                                        ? AlertDeliveryService.<Boolean>refuse(
                                                used
                                                        + (used == 1
                                                                ? " rule sends"
                                                                : " rules send")
                                                        + " here. Point them somewhere else"
                                                        + " first.")
                                        : repository.delete(id).invoke(ignored -> sender.forget()));
    }

    /**
     * Sends a message and says what happened.
     *
     * <p>Down the same path a real alert takes, deliberately. A check that only opened a connection
     * would pass for a webhook that answers 404 and for a mail server that refuses the sender
     * address — both of which are how a delivery is usually wrong.
     */
    public Uni<AlertDeliveryCheck> check(Long id) {
        return repository
                .forUse(id)
                .flatMap(
                        delivery -> {
                            if (delivery == null) {
                                return Uni.createFrom()
                                        .failure(new AlertRefusedException("No such delivery"));
                            }
                            return sender.deliver(delivery, sample(delivery))
                                    .map(
                                            sent ->
                                                    new AlertDeliveryCheck(
                                                            sent.outcome() == DeliveryOutcome.SENT,
                                                            sent.detail()));
                        });
    }

    /**
     * The message a check sends.
     *
     * <p>An ordinary notice rather than a special one, so what arrives is what an alert will look
     * like — including the sentence, which is the part somebody is actually judging.
     */
    private static AlertNotice sample(AlertDelivery delivery) {
        return new AlertNotice(
                null,
                "Test from Keydra",
                null,
                delivery.name,
                null,
                EventKind.FIRED,
                AlertMetric.MEMORY_FILL_PERCENT,
                Comparison.ABOVE,
                91.0,
                90.0,
                Instant.now());
    }

    /**
     * Refuses a delivery that could not send anything, while somebody is still looking at it.
     *
     * <p>Now rather than at three in the morning. A delivery missing the one field its kind needs
     * fails on the alert it was configured for, which is the worst possible moment to find out —
     * the message that would have said something is the message that is lost.
     */
    private Uni<Void> validate(AlertDelivery delivery) {
        return switch (delivery.kind) {
            case WEBHOOK -> validateWebhook(delivery);
            case EMAIL -> validateMail(delivery);
            case TELEGRAM ->
                    needs(
                            delivery,
                            "A Telegram delivery needs the bot's token",
                            "A Telegram delivery needs the chat to post into");
            case SLACK ->
                    needs(
                            delivery,
                            "A Slack delivery needs the bot's token",
                            "A Slack delivery needs the channel to post into");
            case WHATSAPP -> validateWhatsApp(delivery);
        };
    }

    /**
     * Whether Keydra will post to this address at all.
     *
     * <p>Two questions and they are not the same one. Whether it is a URL is a typo check. Whether
     * it is an address this server will make a request to is the other, and it is asked here
     * because managing an alert is an operator's to do — the shortest path anybody has from "may
     * configure an alert" to "holds this deployment's cloud credentials" is a webhook pointed at a
     * metadata service. {@link EgressGuard} says what is refused and why.
     *
     * <p>The refusal reaches the person editing the delivery, in the guard's own words, because the
     * only useful thing to say about a blocked address is which kind of address it is.
     */
    private Uni<Void> validateWebhook(AlertDelivery delivery) {
        if (delivery.url == null || delivery.url.isBlank()) {
            return refuse("A webhook needs an address to post to");
        }
        if (!AlertSender.isPostable(delivery.url)) {
            return refuse("That address is not one Keydra can post to: it needs http or https");
        }
        return egress.check(delivery.url)
                .onFailure(BlockedAddressException.class)
                .recoverWithUni(blocked -> refuse(blocked.getMessage()));
    }

    private Uni<Void> validateMail(AlertDelivery delivery) {
        if (delivery.smtpHost == null || delivery.smtpHost.isBlank()) {
            return refuse("Mail needs a server to send through");
        }
        if (delivery.toAddresses == null || delivery.toAddresses.isBlank()) {
            return refuse("Mail needs somebody to send to");
        }
        if (delivery.fromAddress == null || delivery.fromAddress.isBlank()) {
            return refuse(
                    "Mail needs an address to come from; most servers refuse a message"
                            + " without one");
        }
        return Uni.createFrom().voidItem();
    }

    private Uni<Void> validateWhatsApp(AlertDelivery delivery) {
        Uni<Void> shared =
                needs(
                        delivery,
                        "A WhatsApp delivery needs an access token",
                        "A WhatsApp delivery needs the number to message");
        if (delivery.senderId == null || delivery.senderId.isBlank()) {
            return refuse(
                    "A WhatsApp delivery needs the phone number id it sends from; the Cloud API"
                            + " dashboard shows it beside the number");
        }
        return shared;
    }

    /** The two fields the chat tools have in common, refused in the words of the kind asking. */
    private Uni<Void> needs(AlertDelivery delivery, String withoutToken, String withoutRecipient) {
        if (delivery.apiToken == null || delivery.apiToken.isBlank()) {
            return refuse(withoutToken);
        }
        if (delivery.recipient == null || delivery.recipient.isBlank()) {
            return refuse(withoutRecipient);
        }
        return Uni.createFrom().voidItem();
    }

    private static <T> Uni<T> refuse(String why) {
        return Uni.createFrom().failure(new AlertRefusedException(why));
    }

    private static List<AlertDeliverySummary> append(
            List<AlertDeliverySummary> summaries, AlertDeliverySummary one) {
        return java.util.stream.Stream.concat(summaries.stream(), java.util.stream.Stream.of(one))
                .toList();
    }
}
