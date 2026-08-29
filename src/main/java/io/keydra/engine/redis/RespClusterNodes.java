package io.keydra.engine.redis;

import io.keydra.engine.ClusterNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses {@code CLUSTER NODES}.
 *
 * <p>The reply is one line per node, space separated:
 *
 * <pre>
 * &lt;id&gt; &lt;ip:port@cport[,hostname]&gt; &lt;flags&gt; &lt;primary&gt; &lt;ping-sent&gt; &lt;pong-recv&gt;
 * &lt;epoch&gt; &lt;link-state&gt; [&lt;slot&gt; ...]
 * </pre>
 *
 * <p>Pure text handling, kept out of the engine so it can be tested against real replies without a
 * cluster to hand — which matters here, because a three-node cluster is an expensive thing to stand
 * up for a parser test.
 */
final class RespClusterNodes {

    private RespClusterNodes() {}

    /** Fixed positions in a node line, up to the slots which take the rest. */
    private static final int ID = 0;

    private static final int ADDRESS = 1;
    private static final int FLAGS = 2;
    private static final int PRIMARY = 3;
    private static final int LINK_STATE = 7;
    private static final int FIRST_SLOT = 8;

    static List<ClusterNode> parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }

        List<ClusterNode> nodes = new ArrayList<>();
        for (String line : reply.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split(" ");
            if (parts.length <= LINK_STATE) {
                continue;
            }

            List<String> flags = Arrays.asList(parts[FLAGS].split(","));
            nodes.add(
                    new ClusterNode(
                            parts[ID],
                            clientAddress(parts[ADDRESS]),
                            flags.contains("master")
                                    ? ClusterNode.ROLE_PRIMARY
                                    : ClusterNode.ROLE_REPLICA,
                            flags.contains("myself"),
                            // A primary reports "-" for the primary it replicates.
                            "-".equals(parts[PRIMARY]) ? null : parts[PRIMARY],
                            slots(parts),
                            parts[LINK_STATE],
                            flags,
                            migrations(parts)));
        }
        return nodes;
    }

    /**
     * Strips the bus port and any hostname, leaving what a client would connect to.
     *
     * <p>The field is {@code ip:port@cport} and may carry {@code ,hostname} after that. The bus
     * port is for nodes talking to each other and is not a thing anything else can use.
     */
    private static String clientAddress(String field) {
        int bus = field.indexOf('@');
        return bus == -1 ? field : field.substring(0, bus);
    }

    /**
     * Reads the slots on the move.
     *
     * <p>Written {@code [slot-><node-id>]} when this node is handing one over and {@code
     * [slot-<-<node-id>]} when it is taking one on. They are deliberately not counted among the
     * slots a node serves — a slot mid-flight is neither here nor there, and adding it to both ends
     * would make a cluster look as though it served more than sixteen thousand of them.
     *
     * <p>Read rather than skipped because this is the only part of a cluster's description that is
     * happening rather than being, and a topology that shows the arrangement but not the reshard
     * running through it is showing yesterday's arrangement.
     *
     * <p><strong>Only the node being asked reports its own.</strong> Measured, not assumed: with
     * one slot moving between two nodes, each of those two names it on its own {@code myself} line
     * and the third node names nothing. So a move is seen when Keydra's connection lands on one of
     * its two ends and not otherwise. Seeing every move would mean asking every node in turn, which
     * is a round trip per node for a page that is not the reason anybody opened Keydra — and half
     * an answer that is right is better than a whole one nobody asked for.
     */
    private static List<ClusterNode.SlotMigration> migrations(String[] parts) {
        List<ClusterNode.SlotMigration> moving = new ArrayList<>();
        for (int i = FIRST_SLOT; i < parts.length; i++) {
            String entry = parts[i];
            if (!entry.startsWith("[") || !entry.endsWith("]")) {
                continue;
            }
            String inside = entry.substring(1, entry.length() - 1);
            int out = inside.indexOf("->-");
            int in = inside.indexOf("-<-");
            int marker = out >= 0 ? out : in;
            if (marker < 0) {
                continue;
            }
            try {
                moving.add(
                        new ClusterNode.SlotMigration(
                                Integer.parseInt(inside.substring(0, marker)),
                                out >= 0
                                        ? ClusterNode.SlotMigration.Direction.OUT
                                        : ClusterNode.SlotMigration.Direction.IN,
                                inside.substring(marker + 3)));
            } catch (NumberFormatException notASlot) {
                // Same reasoning as below: an entry this parser does not recognise is skipped.
            }
        }
        return moving;
    }

    /**
     * Reads the slot ranges a node serves.
     *
     * <p>Entries in brackets are slots mid-migration; they belong to the node only for as long as
     * the migration lasts, so they are not reported as served. {@link #migrations} reads those.
     */
    private static List<ClusterNode.SlotRange> slots(String[] parts) {
        List<ClusterNode.SlotRange> ranges = new ArrayList<>();
        for (int i = FIRST_SLOT; i < parts.length; i++) {
            String entry = parts[i];
            if (entry.startsWith("[")) {
                continue;
            }
            int dash = entry.indexOf('-');
            try {
                if (dash == -1) {
                    int slot = Integer.parseInt(entry);
                    ranges.add(new ClusterNode.SlotRange(slot, slot));
                } else {
                    ranges.add(
                            new ClusterNode.SlotRange(
                                    Integer.parseInt(entry.substring(0, dash)),
                                    Integer.parseInt(entry.substring(dash + 1))));
                }
            } catch (NumberFormatException notASlot) {
                // A field this parser does not recognise is skipped rather than fatal:
                // the format has grown fields before and will again.
            }
        }
        return ranges;
    }
}
