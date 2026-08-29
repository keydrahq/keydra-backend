package io.keydra.pubsub.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Channels and patterns to listen on.
 *
 * <p>Both in one request because a subscription is one connection either way, and splitting them
 * would mean two connections to the same target listening for the same conversation.
 */
@Schema(name = "SubscriptionRequest", description = "Channels and patterns to subscribe to")
public record SubscriptionRequest(List<String> channels, List<String> patterns) {

    public List<String> channels() {
        return channels == null ? List.of() : channels;
    }

    public List<String> patterns() {
        return patterns == null ? List.of() : patterns;
    }
}
