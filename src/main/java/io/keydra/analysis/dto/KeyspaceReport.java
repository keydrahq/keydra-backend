package io.keydra.analysis.dto;

import io.keydra.engine.KeySize;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Where a target's memory went, and how much of it will ever be released.
 *
 * <p>Every figure here is measured over a sample rather than the whole keyspace: MEMORY USAGE costs
 * a round trip per key, and a million of them would cost far more than the answer is worth. What
 * the sample is good for is proportion — "the cache namespace is sixty percent of your memory" is
 * true of a fair sample of ten thousand keys and does not become truer for a million.
 *
 * @param sampled how many keys were actually measured
 * @param keysInDatabase how many the server says are there, so the sample can be judged
 * @param bytesSampled what those keys occupy
 * @param namespaces where the memory is, grouped by the first segment of the key
 * @param types what shapes hold it
 * @param expiry how much of it will ever be released on its own
 * @param largest the individual keys worth knowing about
 */
@Schema(name = "KeyspaceReport", description = "Where a target's memory went")
public record KeyspaceReport(
        int sampled,
        long keysInDatabase,
        long bytesSampled,
        List<NamespaceUsage> namespaces,
        List<TypeUsage> types,
        List<ExpiryBand> expiry,
        List<KeySize> largest) {}
