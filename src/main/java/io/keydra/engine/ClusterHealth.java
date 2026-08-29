package io.keydra.engine;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a cluster says about itself, as opposed to what its node list implies.
 *
 * <p>The node list already says who is there and which slots each one claims. It does not say
 * whether the cluster considers itself able to serve, and those are different questions: every slot
 * can be assigned while the cluster refuses every request, because the node holding some of them is
 * failing and the rest of them voted on it. A picture drawn from the node list alone shows a full
 * bar and a healthy row of cards for a cluster that is down.
 *
 * <p>So this is the cluster's own verdict, read from {@code CLUSTER INFO}, and it is the first
 * thing the topology page says.
 *
 * @param state what the cluster thinks it is — "ok" when it will serve, "fail" when it will not
 * @param slotsAssigned how many of the 16384 have an owner at all
 * @param slotsOk how many of those have an owner that is answering; the difference is what an
 *     assignment bar cannot show
 * @param slotsPfail slots whose owner one node suspects has gone
 * @param slotsFail slots whose owner the cluster has agreed has gone
 * @param knownNodes every node in the arrangement, including replicas and nodes serving nothing
 * @param size how many of them actually serve slots, which is the number of shards
 * @param currentEpoch the configuration version, which rises with every failover — worth showing
 *     because a cluster whose epoch is far ahead of yesterday's has been electing
 */
@Schema(name = "ClusterHealth", description = "What a cluster reports about its own state")
public record ClusterHealth(
        String state,
        int slotsAssigned,
        int slotsOk,
        int slotsPfail,
        int slotsFail,
        int knownNodes,
        int size,
        long currentEpoch) {

    /** What a cluster answers when it will serve. */
    public static final String STATE_OK = "ok";

    /** Whether the cluster says it is serving. */
    public boolean isServing() {
        return STATE_OK.equalsIgnoreCase(state);
    }
}
