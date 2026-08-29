package io.keydra.events.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.keydra.cluster.service.InstanceId;
import io.keydra.events.dto.Notification;
import io.keydra.events.ws.Notifications;
import io.keydra.store.service.KeydraStore;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Fan-out point for server-side state changes.
 *
 * <p>Any bean can publish here; the hub serialises the envelope once and pushes it to every open
 * subscriber of {@link Notifications}. Mirrors Cryostat's MessagingServer, minus the hand-rolled
 * session map — {@code websockets-next} tracks open connections for us.
 *
 * <p>Since phase 17 there can be more than one Keydra, and a broadcast that reached only its own
 * sockets reached only the people whose browsers happened to land on this instance. So when the
 * store is a shared one, every envelope goes out on a channel as well, and what arrives from
 * another instance is pushed to this one's sockets. The socket that has been the reason nothing
 * polls since phase 1 stops being that the moment there are two of us, and this is what puts it
 * back.
 *
 * <p>An envelope names the instance that wrote it and an instance ignores its own, which is what
 * stops a broadcast arriving twice on the machine that made it.
 *
 * <p>An envelope also names the target it is about, and that is what decides who receives it. This
 * used to go to every open socket on the strength of somebody being signed in, which meant an
 * account with a grant on one target was sent a running description of every other one — the keys
 * that changed on it, a reading from it, the text of an alert about it. {@link SocketAudience} is
 * the filter, and it is the same filter the connections page applies. An envelope naming no target
 * is news about Keydra and still goes to everybody.
 */
@ApplicationScoped
public class NotificationHub {

    /** The channel every instance publishes its notifications on and listens to. */
    public static final String CHANNEL = "notifications";

    /**
     * The field naming the instance that wrote an envelope.
     *
     * <p>Added on the way out to the store and taken off before anything is sent to a browser: it
     * is how instances tell their own broadcasts apart, and it is nothing a page has any use for.
     */
    private static final String FROM = "from";

    /**
     * The field naming the target an envelope is about.
     *
     * <p>Read back off an envelope that arrived from another instance, so the filter there is the
     * same one the instance that wrote it applied. Unlike {@link #FROM} this one stays on: a page
     * has no use for it either, but stripping it would mean rebuilding the envelope on the way to
     * every socket rather than serialising it once.
     */
    private static final String ABOUT = "connectionId";

    private final OpenConnections connections;
    private final ObjectMapper mapper;
    private final KeydraStore store;
    private final SocketAudience audience;
    private final String instanceId = InstanceId.get();

    @Inject
    NotificationHub(
            OpenConnections connections,
            ObjectMapper mapper,
            KeydraStore store,
            SocketAudience audience) {
        this.connections = connections;
        this.mapper = mapper;
        this.store = store;
        this.audience = audience;
    }

    private static final Logger LOG = Logger.getLogger(NotificationHub.class);

    /**
     * What this instance has put on the bus and taken off it.
     *
     * <p>Counted rather than measured, and cumulative rather than a rate: a counter is a number
     * that only goes up, so two readings a few seconds apart give a rate and one reading gives
     * nothing to disagree about. Whoever reads them does the arithmetic — which is what stops this
     * from being a second, slower clock.
     *
     * <p>They are the only evidence that instances are talking to each other at all. Two Keydras do
     * not connect to one another; they both connect to the store and shout down the same channel,
     * and these two numbers are that conversation seen from one end.
     */
    private final java.util.concurrent.atomic.AtomicLong published =
            new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong received =
            new java.util.concurrent.atomic.AtomicLong();

    /** How many envelopes this instance has put on the bus for the others. */
    public long publishedCount() {
        return published.get();
    }

    /** And how many it has taken off it — which is only ever what somebody else put there. */
    public long receivedCount() {
        return received.get();
    }

    /**
     * Starts listening for what the other instances broadcast.
     *
     * <p>Only when the store is shared. With the in-process one there is nobody else to hear from,
     * and subscribing would mean this instance handing itself back everything it just sent.
     */
    void onStart(@Observes StartupEvent ignored) {
        if (store.isShared()) {
            store.subscribe(CHANNEL, this::onRemote);
        }
    }

    /**
     * An envelope another instance broadcast.
     *
     * <p>Pushed to this instance's sockets as it arrived, minus the field that says where it came
     * from. Anything this instance wrote is dropped: it has already gone to these sockets once.
     */
    private void onRemote(String json) {
        try {
            JsonNode envelope = mapper.readTree(json);
            JsonNode from = envelope.get(FROM);
            if (from != null && instanceId.equals(from.asText())) {
                return;
            }
            received.incrementAndGet();
            ((ObjectNode) envelope).remove(FROM);
            JsonNode about = envelope.get(ABOUT);
            push(
                    mapper.writeValueAsString(envelope),
                    about == null || about.isNull() ? null : about.asLong());
        } catch (Exception unreadable) {
            // Something else is writing on this channel, or an older Keydra is. Neither is
            // worth a stack trace on every message.
            LOG.debugf("Ignoring an envelope that could not be read: %s", unreadable.getMessage());
        }
    }

    /** Serialises and pushes the envelope to every open connection. Never throws. */
    public void broadcast(Notification notification) {
        String json;
        try {
            json = mapper.writeValueAsString(notification);
        } catch (JsonProcessingException e) {
            LOG.errorf(
                    e, "Could not serialise notification of category %s", notification.category());
            return;
        }
        push(json, notification.connectionId());
        share(notification);
    }

    /**
     * Sends the envelope to the other instances, when there are any.
     *
     * <p>After the local push rather than before it: the people attached to this instance are the
     * ones whose action this usually is, and they should not wait on a round trip to a server for
     * news of what they just did.
     */
    private void share(Notification notification) {
        if (!store.isShared()) {
            return;
        }
        try {
            ObjectNode envelope = mapper.valueToTree(notification);
            envelope.put(FROM, instanceId);
            store.publish(CHANNEL, mapper.writeValueAsString(envelope))
                    .subscribe()
                    .with(
                            // Counted where it succeeded rather than where it was attempted: an
                            // envelope the store would not take is not one the others received.
                            ignored -> published.incrementAndGet(),
                            failure ->
                                    LOG.debugf(
                                            failure,
                                            "Could not share a %s with the other instances",
                                            notification.category()));
        } catch (JsonProcessingException impossible) {
            LOG.debug("Could not write an envelope for the other instances", impossible);
        }
    }

    private void push(String json, Long connectionId) {
        for (WebSocketConnection connection : connections) {
            if (!Notifications.ENDPOINT_ID.equals(connection.endpointId())) {
                continue;
            }
            if (!audience.maySee(connection, connectionId)) {
                continue;
            }
            connection
                    .sendText(json)
                    .subscribe()
                    .with(
                            ignored -> {},
                            failure ->
                                    LOG.debugf(
                                            failure,
                                            "Dropping notification for connection %s",
                                            connection.id()));
        }
    }

    /**
     * For news about Keydra rather than about any one target.
     *
     * <p>Everybody who may be signed in receives it, so what goes through here must name nothing
     * that belongs to a target. When in doubt it belongs in {@link #broadcast(String, Long,
     * Object)} with the target named — an envelope that reaches too few people is a page that
     * refreshes a moment late, and one that reaches too many is the thing this filter exists for.
     */
    public void broadcast(String category, Object payload) {
        broadcast(Notification.of(category, payload));
    }

    /** For news about one target, which reaches only those who may see that target. */
    public void broadcast(String category, Long connectionId, Object payload) {
        broadcast(Notification.about(category, connectionId, payload));
    }
}
