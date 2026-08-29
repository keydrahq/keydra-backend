package io.keydra.keys.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Copy a key to a new name.
 *
 * @param replace when false the copy fails if the target already exists, so an accidental overwrite
 *     of unrelated data takes a deliberate flag
 */
@Schema(name = "CopyKeyRequest", description = "Copy a key to a new name")
public record CopyKeyRequest(@NotBlank String from, @NotBlank String to, boolean replace) {}
