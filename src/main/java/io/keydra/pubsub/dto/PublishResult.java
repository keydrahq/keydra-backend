package io.keydra.pubsub.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * @param receivers how many subscribers the store delivered it to; zero means nobody was listening,
 *     which is a normal outcome and not an error
 */
@Schema(name = "PublishResult", description = "How many subscribers received the message")
public record PublishResult(long receivers) {}
