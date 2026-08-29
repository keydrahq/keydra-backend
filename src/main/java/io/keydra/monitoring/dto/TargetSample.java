package io.keydra.monitoring.dto;

import io.keydra.engine.MetricsSample;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;

/**
 * One target's reading, or the absence of one.
 *
 * <p>A pair rather than a map keyed by id, for the same reason everything else here is: GraphQL has
 * no map type, and a named pair says what it is where a generated entry type does not.
 *
 * @param connectionId which target this is about
 * @param sample what it reported, or null when it would not answer — which is a gap in a summary
 *     rather than a failure of one
 */
@Name("TargetSample")
@Description("One target's reading, or nothing when it would not answer")
public record TargetSample(Long connectionId, MetricsSample sample) {}
