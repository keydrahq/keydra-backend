package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * How a target is arranged, for stores that are arranged at all.
 *
 * <p>Optional on {@link KeyValueEngine} like the other capabilities: a single-process store has no
 * topology to describe, and saying so with an empty Optional beats a method that answers with an
 * empty list as though it had looked.
 */
public interface Topology {

    /** The nodes of a cluster, as the node being asked sees them. */
    Uni<List<ClusterNode>> clusterNodes(ConnectionProfile profile);

    /** The primaries a sentinel is watching, with the replicas it has discovered. */
    Uni<List<SentinelMaster>> sentinelMasters(ConnectionProfile profile);

    /**
     * What the cluster says about its own state, or null when it is not one or will not say.
     *
     * <p>A default of null rather than another Optional on the engine: this is a question inside a
     * capability a store already declared it has, and a topology that can list nodes but cannot be
     * asked how it is doing is a real shape — a store with nodes and no notion of a quorum.
     */
    default Uni<ClusterHealth> clusterHealth(ConnectionProfile profile) {
        return Uni.createFrom().nullItem();
    }
}
