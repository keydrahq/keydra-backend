package io.keydra.values.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How many elements a change affected.
 *
 * <p>Its own file in {@code dto} rather than a record inside the resource. A resource is transport;
 * what it answers with is not.
 *
 * @param affected how many elements the change touched
 */
@Schema(name = "MutationResult", description = "How many elements a change affected")
public record MutationResult(long affected) {}
