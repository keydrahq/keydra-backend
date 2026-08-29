package io.keydra.alerts.service;

import io.keydra.alerts.dto.AlertNotice;
import io.keydra.alerts.entity.EventKind;
import io.keydra.alerts.entity.InstanceNoticeDelivery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Telling somebody about Keydra's own troubles.
 *
 * <p>Phase 49 made "does this answer" a fact that is checked and written down, and until this
 * existed the whole of it rested on somebody having the page open. A backup destination whose
 * credentials expired is otherwise discovered at three in the morning three weeks later — which is
 * the sentence phase 49 started from and only half answered.
 *
 * <p><b>Not a rule.</b> The alerts domain asks which metric, which comparison, what number, for how
 * long; none of those has an answer here. A destination is reachable or it is not. So what is
 * configurable is the only thing that varies — where the news goes — and it goes to the same
 * destinations the rules use, because a second list of channels would be a second place to rotate a
 * token.
 *
 * <p>Here rather than in {@code cluster}, which is what notices the change: this is the domain that
 * owns what a message looks like and where messages go, and the caller asks for one by saying what
 * happened.
 */
@ApplicationScoped
public class InstanceNotices {

    private static final Logger LOG = Logger.getLogger(InstanceNotices.class);

    private final AlertSender sender;

    @Inject
    InstanceNotices(AlertSender sender) {
        this.sender = sender;
    }

    /** Which destinations hear about Keydra itself, for the page that offers the choice. */
    @WithSession
    public Uni<List<Long>> hearing() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select deliveryId from InstanceNoticeDelivery"
                                                        + " order by deliveryId",
                                                Long.class)
                                        .getResultList());
    }

    /** Replaces the list wholesale, which is how it arrives from a form. */
    @WithTransaction
    public Uni<List<Long>> hearing(List<Long> deliveryIds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from InstanceNoticeDelivery")
                                        .executeUpdate()
                                        .flatMap(
                                                ignored -> {
                                                    Uni<Void> chain = Uni.createFrom().voidItem();
                                                    for (Long id :
                                                            deliveryIds.stream()
                                                                    .filter(
                                                                            java.util.Objects
                                                                                    ::nonNull)
                                                                    .distinct()
                                                                    .toList()) {
                                                        InstanceNoticeDelivery row =
                                                                new InstanceNoticeDelivery();
                                                        row.deliveryId = id;
                                                        chain =
                                                                chain.flatMap(
                                                                        done ->
                                                                                session.persist(
                                                                                        row));
                                                    }
                                                    return chain;
                                                }))
                .flatMap(ignored -> hearing());
    }

    /**
     * Announces that something Keydra reaches has changed its mind about answering.
     *
     * <p>Called on the edge and never on the state: a destination that has been down since Tuesday
     * is one message on Tuesday rather than one every ten minutes for a week, which is the
     * difference between a channel somebody reads and one somebody mutes. Both edges, because a
     * channel that only hears bad news accumulates alarms nobody knows the end of.
     *
     * <p>Never fails its caller. What it is reporting on is already recorded; a channel that would
     * not take the message must not also stop the walk that found it.
     *
     * @param subject what kind of thing this is — "backup destination", "identity provider"
     * @param name what somebody called it
     * @param answering whether it answers now
     * @param detail what it said when it did not, or null
     */
    public Uni<Void> changed(String subject, String name, boolean answering, String detail) {
        return hearing()
                .flatMap(
                        deliveries -> {
                            if (deliveries.isEmpty()) {
                                return Uni.createFrom().voidItem();
                            }
                            AlertNotice notice = notice(subject, name, answering, detail);
                            Uni<Void> chain = Uni.createFrom().voidItem();
                            for (Long deliveryId : deliveries) {
                                chain =
                                        chain.flatMap(
                                                ignored ->
                                                        sender.send(deliveryId, notice)
                                                                .replaceWithVoid());
                            }
                            return chain;
                        })
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.warnf(
                                    "Could not announce that %s %s is %s: %s",
                                    subject,
                                    name,
                                    answering ? "answering again" : "not answering",
                                    failure.toString());
                            return null;
                        });
    }

    /**
     * The same message an alert sends, because it is the same kind of news.
     *
     * <p>{@code NO_ANSWER} already means "the measurement was that there was none", and {@code
     * AlertWording} already turns it into the two sentences this needs. A second wording would be
     * two descriptions of one event that can disagree.
     */
    private static AlertNotice notice(
            String subject, String name, boolean answering, String detail) {
        return new AlertNotice(
                null,
                answering ? "Reachable again" : (detail == null ? "Not answering" : detail),
                null,
                name,
                subject,
                answering ? EventKind.CLEARED : EventKind.FIRED,
                io.keydra.alerts.entity.AlertMetric.NO_ANSWER,
                io.keydra.alerts.entity.Comparison.ABOVE,
                null,
                0,
                Instant.now());
    }
}
