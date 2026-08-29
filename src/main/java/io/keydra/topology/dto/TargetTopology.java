package io.keydra.topology.dto;

import io.keydra.connections.dto.ServerInfo;
import io.keydra.engine.Capabilities;
import io.keydra.engine.ClusterHealth;
import io.keydra.engine.ClusterNode;
import io.keydra.engine.SentinelMaster;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Everything the UI needs to decide what to draw and what to offer.
 *
 * <p>One response rather than three, because the three answers are only meaningful together: an
 * empty node list means "standalone" when the mode says standalone and "we could not reach the
 * cluster" when it says cluster, and a feature list without the flavor that produced it invites the
 * reader to guess.
 *
 * @param server what the target reported about itself
 * @param capabilities what it can actually do, asked of it directly
 * @param nodes cluster nodes, empty when the target is not clustered
 * @param sentinelMasters primaries a sentinel is watching, empty when it is not one
 * @param health what the cluster says about itself, null when it is not one or would not say — the
 *     node list says who is there, and this says whether they are serving
 */
@Schema(name = "TargetTopology", description = "How a target is arranged and what it supports")
public record TargetTopology(
        ServerInfo server,
        Capabilities capabilities,
        List<ClusterNode> nodes,
        List<SentinelMaster> sentinelMasters,
        ClusterHealth health) {}
