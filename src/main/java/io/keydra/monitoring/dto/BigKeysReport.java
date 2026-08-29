package io.keydra.monitoring.dto;

import io.keydra.engine.KeySize;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The largest keys found in a sample of the keyspace.
 *
 * <p>Carries what it was drawn from, because the ranking means nothing without it: "the ten largest
 * keys" and "the ten largest of the thousand we looked at" are different claims, and only the
 * second one is true.
 *
 * @param sampled how many keys were measured
 * @param totalBytes how much memory those keys occupy between them
 * @param largest the biggest of them, largest first
 */
@Schema(name = "BigKeysReport", description = "The largest keys in a sample of the keyspace")
public record BigKeysReport(int sampled, long totalBytes, List<KeySize> largest) {}
