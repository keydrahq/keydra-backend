package io.keydra.engine;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One message delivered to a subscriber.
 *
 * @param channel the channel it was published to
 * @param pattern the pattern that matched, or null when the subscription named the channel
 * @param payload the message body, as text
 */
@Schema(name = "ChannelMessage", description = "A message delivered to a subscriber")
public record ChannelMessage(String channel, String pattern, String payload) {}
