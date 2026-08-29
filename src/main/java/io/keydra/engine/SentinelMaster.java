package io.keydra.engine;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A primary a sentinel is watching, and what it knows about it.
 *
 * @param name the name the sentinel knows it by, which is what a client asks for
 * @param address where the primary is now — the point of sentinel is that this changes
 * @param status what the sentinel currently believes: ok, subjectively down, objectively down
 * @param quorum how many sentinels must agree before a failover
 * @param replicas the replicas the sentinel has discovered
 */
@Schema(name = "SentinelMaster", description = "A primary watched by sentinel")
public record SentinelMaster(
        String name, String address, String status, Integer quorum, List<Replica> replicas) {

    /**
     * @param linkStatus whether the sentinel can currently reach it
     */
    @Schema(name = "SentinelReplica", description = "A replica discovered by sentinel")
    public record Replica(String address, String status, String linkStatus) {}
}
