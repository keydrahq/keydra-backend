package io.keydra.engine;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One reading of a server's vital signs.
 *
 * <p>Deliberately small, and deliberately neutral. A RESP server's INFO reports well over a hundred
 * fields and another store would report a different hundred; these are the few that answer the
 * questions a person actually opens a dashboard to ask — is it busy, is it full, is it being
 * useful, and who is on it.
 *
 * <p>Every field is nullable because a store may not report it. A missing number is shown as
 * missing rather than as zero, which would read as "nothing is happening".
 *
 * @param at when the reading was taken
 * @param memoryUsedBytes memory the store is holding
 * @param memoryPeakBytes the most it has held since it started
 * @param memoryMaxBytes the ceiling it was configured with, if any
 * @param connectedClients how many clients are attached
 * @param opsPerSecond commands per second as the store itself counts them
 * @param totalCommands commands processed since start, from which a rate can be derived
 * @param keyspaceHits lookups that found something
 * @param keyspaceMisses lookups that did not
 * @param keyCount keys in the database being watched
 * @param uptimeSeconds how long the store has been up
 * @param evictedKeys keys dropped to stay under the memory ceiling
 * @param expiredKeys keys removed because their time ran out
 */
@Schema(name = "MetricsSample", description = "One reading of a server's vital signs")
public record MetricsSample(
        Instant at,
        Long memoryUsedBytes,
        Long memoryPeakBytes,
        Long memoryMaxBytes,
        Long connectedClients,
        Long opsPerSecond,
        Long totalCommands,
        Long keyspaceHits,
        Long keyspaceMisses,
        Long keyCount,
        Long uptimeSeconds,
        Long evictedKeys,
        Long expiredKeys) {

    /**
     * Hits as a fraction of lookups, or null when nothing has been looked up.
     *
     * <p>Computed rather than stored: zero hits and zero misses is not a hit ratio of zero, it is
     * the absence of one, and a dashboard that draws 0% for an idle server is lying about it.
     */
    public Double hitRatio() {
        if (keyspaceHits == null || keyspaceMisses == null) {
            return null;
        }
        long lookups = keyspaceHits + keyspaceMisses;
        return lookups == 0 ? null : (double) keyspaceHits / lookups;
    }
}
