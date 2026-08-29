package io.keydra.engine;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One node of a clustered target.
 *
 * @param id the node's own identifier, which is how replicas name their primary
 * @param address host and port clients connect to
 * @param role primary or replica
 * @param isSelf true for the node that answered the question
 * @param primaryId the primary this node replicates, or null when it is one
 * @param slots hash-slot ranges this node serves, empty for a replica
 * @param linkState whether the node answering can currently reach this one
 * @param flags whatever else the store said about it, unparsed
 * @param migrations slots on the move between this node and another, empty in the ordinary case — a
 *     cluster is only resharding while somebody is resharding it
 */
@Schema(name = "ClusterNode", description = "One node of a clustered target")
public record ClusterNode(
        String id,
        String address,
        String role,
        boolean isSelf,
        String primaryId,
        List<SlotRange> slots,
        String linkState,
        List<String> flags,
        List<SlotMigration> migrations) {

    public static final String ROLE_PRIMARY = "primary";
    public static final String ROLE_REPLICA = "replica";

    /**
     * One slot on its way between two nodes.
     *
     * <p>Reported by the node at each end of the move, in brackets, and thrown away by every
     * console that reads {@code CLUSTER NODES} for the ranges alone. It is the only thing in a
     * cluster's description that is happening rather than being — while a reshard runs, a slot is
     * neither here nor there, and the client is redirected mid-flight by an ASK.
     *
     * @param slot which of the 16384 is moving
     * @param direction OUT when this node is handing it over, IN when it is taking it
     * @param peerId the node at the other end of the move
     */
    @Schema(name = "SlotMigration", description = "A hash slot moving between two nodes")
    public record SlotMigration(int slot, Direction direction, String peerId) {

        /** Which way a slot is going, from the point of view of the node that reported it. */
        public enum Direction {
            /** This node is handing the slot over. */
            OUT,
            /** This node is taking the slot on. */
            IN
        }
    }

    /** A contiguous run of hash slots, inclusive at both ends. */
    @Schema(name = "SlotRange", description = "A contiguous run of hash slots")
    public record SlotRange(int from, int to) {

        public int count() {
            return to - from + 1;
        }
    }
}
