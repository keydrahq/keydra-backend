package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * A store's publish/subscribe facility, for stores that have one.
 *
 * <p>Optional for the same reason {@link CommandConsole} is: a key-value store need not carry
 * messages, and an engine that cannot should say so rather than inherit a method that throws.
 */
public interface MessageBus {

    /**
     * Opens a subscription and streams what arrives on it.
     *
     * <p>The stream is the subscription: cancelling it unsubscribes and releases whatever the store
     * needed to hold it open. That matters here because subscribing puts a RESP connection into a
     * mode where it accepts nothing else, so the connection cannot be a shared one and must be
     * given back when the stream ends.
     *
     * @param channels exact channel names
     * @param patterns glob patterns, matched by the store
     */
    Multi<ChannelMessage> subscribe(
            ConnectionProfile profile, List<String> channels, List<String> patterns);

    /**
     * @return how many subscribers the store delivered the message to
     */
    Uni<Long> publish(ConnectionProfile profile, String channel, String payload);
}
