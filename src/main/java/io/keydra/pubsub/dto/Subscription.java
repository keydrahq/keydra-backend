package io.keydra.pubsub.dto;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a target is currently being listened to for.
 *
 * @param messagesReceived how many messages have arrived since it opened, which is the quickest way
 *     to tell a working subscription from a silent one
 */
@Schema(name = "Subscription", description = "An open subscription")
/*
 * Named differently on the second surface, because "Subscription" is one of GraphQL's three root
 * types and a schema may not redefine it. What this is has not changed — it is still the standing
 * arrangement to listen to some channels — and REST still calls it what it always did.
 */
@Name("ChannelSubscription")
public record Subscription(
        Long connectionId,
        List<String> channels,
        List<String> patterns,
        Instant since,
        long messagesReceived) {}
