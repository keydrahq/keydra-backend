package io.keydra.keys.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Outcome of a key mutation.
 *
 * @param affected how many keys the command actually changed, which can be lower than the number
 *     requested when a key expired between listing and acting on it
 */
@Schema(name = "KeyOperationResult", description = "Outcome of a key mutation")
public record KeyOperationResult(long affected) {}
