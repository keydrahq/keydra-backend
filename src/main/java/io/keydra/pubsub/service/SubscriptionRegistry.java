package io.keydra.pubsub.service;

import io.keydra.common.workload.Workload;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.ChannelMessage;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.MessageBus;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.pubsub.dto.PublishResult;
import io.keydra.pubsub.dto.Subscription;
import io.keydra.pubsub.dto.SubscriptionRequest;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * Holds the open subscriptions and forwards what arrives on them.
 *
 * <p>One subscription per target rather than one per channel: a RESP subscription occupies a whole
 * connection, so listening to five channels on one connection is five times cheaper than five
 * connections listening to one each. Changing the channel list replaces the subscription.
 *
 * <p>Messages go out over the notification hub the rest of the application already uses, so a page
 * watching a channel needs no second socket, and a message reaches every tab that is looking.
 */
@ApplicationScoped
public class SubscriptionRegistry implements Workload {

    private static final Logger LOG = Logger.getLogger(SubscriptionRegistry.class);

    /** One open subscription, and the means to close it. */
    private record Open(
            List<String> channels,
            List<String> patterns,
            Instant since,
            AtomicLong received,
            Cancellable cancellable) {}

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final NotificationHub hub;
    private final Map<Long, Open> open = new ConcurrentHashMap<>();

    @Inject
    SubscriptionRegistry(
            ConnectionService connections, EngineSelector engines, NotificationHub hub) {
        this.connections = connections;
        this.engines = engines;
        this.hub = hub;
    }

    void onStop(@Observes ShutdownEvent event) {
        Set.copyOf(open.keySet()).forEach(this::unsubscribe);
    }

    /**
     * Opens a subscription, replacing any this target already had.
     *
     * <p>Replacing rather than adding: the request states what should be listened to, and merging
     * would leave channels open that nobody asked for and no one can name to close.
     */
    public Uni<Subscription> subscribe(Long connectionId, SubscriptionRequest request) {
        return connections.load(connectionId).map(profile -> start(connectionId, profile, request));
    }

    private Subscription start(
            Long connectionId, ConnectionProfile profile, SubscriptionRequest request) {
        unsubscribe(connectionId);

        MessageBus bus =
                engines.forProfile(profile)
                        .messaging()
                        .orElseThrow(
                                () ->
                                        new UnsupportedOperationException(
                                                "This target does not carry messages"));

        AtomicLong received = new AtomicLong();
        Cancellable cancellable =
                bus.subscribe(profile, request.channels(), request.patterns())
                        .subscribe()
                        .with(
                                message -> forward(connectionId, message, received),
                                failure -> {
                                    LOG.debugf(failure, "Subscription to %d ended", connectionId);
                                    open.remove(connectionId);
                                    announce(connectionId, "dropped");
                                });

        Open subscription =
                new Open(
                        List.copyOf(request.channels()),
                        List.copyOf(request.patterns()),
                        Instant.now(),
                        received,
                        cancellable);
        open.put(connectionId, subscription);
        announce(connectionId, "opened");
        return describe(connectionId, subscription);
    }

    private void forward(Long connectionId, ChannelMessage message, AtomicLong received) {
        received.incrementAndGet();
        hub.broadcast(
                NotificationCategory.CHANNEL_MESSAGE,
                connectionId,
                Map.of(
                        "connectionId",
                        connectionId,
                        "channel",
                        message.channel(),
                        "pattern",
                        message.pattern() == null ? "" : message.pattern(),
                        "payload",
                        message.payload()));
    }

    /** Closes the subscription, if there is one. Cancelling the stream is what unsubscribes. */
    public boolean unsubscribe(Long connectionId) {
        Open existing = open.remove(connectionId);
        if (existing == null) {
            return false;
        }
        existing.cancellable().cancel();
        announce(connectionId, "closed");
        return true;
    }

    public List<Subscription> subscriptions() {
        return open.entrySet().stream()
                .map(entry -> describe(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Subscription subscription(Long connectionId) {
        Open existing = open.get(connectionId);
        return existing == null ? null : describe(connectionId, existing);
    }

    public Uni<PublishResult> publish(Long connectionId, String channel, String payload) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile ->
                                engines.forProfile(profile)
                                        .messaging()
                                        .orElseThrow(
                                                () ->
                                                        new UnsupportedOperationException(
                                                                "This target does not carry"
                                                                        + " messages"))
                                        .publish(profile, channel, payload))
                .map(PublishResult::new);
    }

    private static Subscription describe(Long connectionId, Open subscription) {
        return new Subscription(
                connectionId,
                subscription.channels(),
                subscription.patterns(),
                subscription.since(),
                subscription.received().get());
    }

    private void announce(Long connectionId, String state) {
        hub.broadcast(
                NotificationCategory.SUBSCRIPTION_CHANGED,
                connectionId,
                Map.of("connectionId", connectionId, "state", state));
    }

    /**
     * How many subscriptions this instance is holding open.
     *
     * <p>One per target, which is what {@link #open} is keyed by: a RESP subscription occupies a
     * whole connection, so this is a count of connections held against somebody's server because a
     * page is watching a channel on it.
     */
    @Override
    public Snapshot snapshot() {
        return Snapshot.ofStreams(open.size());
    }
}
