package io.keydra.analysis.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One namespace's share of the memory.
 *
 * <p>Grouped by the first segment of the key, which is how people already name things: a key called
 * {@code cache:page:home} belongs to {@code cache}, and the question "what is filling this server"
 * is nearly always answered at that level rather than by any individual key.
 *
 * @param prefix the first segment, or the whole key when it has no delimiter
 * @param keys how many sampled keys are in it
 * @param bytes what they occupy
 * @param neverExpires how many of them have no expiry, which is where memory creep comes from
 */
@Schema(name = "NamespaceUsage", description = "One namespace's share of the memory")
public record NamespaceUsage(String prefix, long keys, long bytes, long neverExpires) {}
