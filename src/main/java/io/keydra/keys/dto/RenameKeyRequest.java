package io.keydra.keys.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Rename a key.
 *
 * @param replace when false the rename fails if the target already exists (RENAMENX), so an
 *     accidental overwrite of unrelated data takes a deliberate flag
 */
@Schema(name = "RenameKeyRequest", description = "Rename a key")
public record RenameKeyRequest(@NotBlank String from, @NotBlank String to, boolean replace) {}
