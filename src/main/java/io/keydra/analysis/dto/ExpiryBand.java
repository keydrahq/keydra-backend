package io.keydra.analysis.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How much of the keyspace will be released, and when.
 *
 * <p>The band that matters most is the first: keys with no expiry at all. A cache whose entries
 * never expire is not a cache, and the memory it holds is never coming back — which is the single
 * most common way a Redis server fills up without anybody doing anything wrong.
 *
 * @param band an identifier the client turns into a label: never, hour, day, week, longer
 * @param keys how many sampled keys fall in it
 * @param bytes what they occupy
 */
@Schema(name = "ExpiryBand", description = "How much of the keyspace will be released, and when")
public record ExpiryBand(String band, long keys, long bytes) {}
