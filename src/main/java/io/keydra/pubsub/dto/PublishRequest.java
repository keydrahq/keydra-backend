package io.keydra.pubsub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** A message to publish. */
@Schema(name = "PublishRequest", description = "A message to publish to a channel")
public record PublishRequest(@NotBlank String channel, @NotNull String payload) {}
