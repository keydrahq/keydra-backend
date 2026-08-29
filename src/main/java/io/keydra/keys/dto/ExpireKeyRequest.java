package io.keydra.keys.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Set or clear a key's time to live.
 *
 * @param ttlSeconds seconds until expiry; {@code null} removes the expiry (PERSIST)
 */
@Schema(name = "ExpireKeyRequest", description = "Set or clear a key's TTL")
public record ExpireKeyRequest(@NotBlank String key, Long ttlSeconds) {}
