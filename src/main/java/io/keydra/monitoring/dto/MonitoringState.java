package io.keydra.monitoring.dto;

import io.keydra.engine.MetricsSample;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Whether a target is being sampled, and what has been collected.
 *
 * @param enabled true while the sampler is running for this target
 * @param heldByRule true when an alert rule is keeping it running, whoever else has stopped
 *     watching — so an interface can say why a switch it turned off is still on rather than looking
 *     broken
 * @param intervalSeconds how often a reading is taken
 * @param samples the readings held, oldest first
 * @param durable true when readings are also written somewhere that outlives this process, so an
 *     interface can offer a window longer than the hour that is kept in memory
 */
@Schema(name = "MonitoringState", description = "Sampling state and the readings collected")
public record MonitoringState(
        boolean enabled,
        boolean heldByRule,
        int intervalSeconds,
        List<MetricsSample> samples,
        boolean durable) {}
