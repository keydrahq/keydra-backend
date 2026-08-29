package io.keydra.monitoring.dto;

import io.keydra.engine.MetricsSample;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Readings over a window, and where they were read from.
 *
 * <p>The source is not decoration. An hour from memory and a month from a store are different
 * claims — one is every reading taken, the other is buckets of them averaged — and an interface
 * that drew both the same way would be inviting somebody to read a smoothed line as a measurement.
 *
 * @param source where the readings came from
 * @param stepSeconds how wide each bucket is; the sampling interval when nothing was aggregated
 * @param samples the readings, oldest first
 */
@Schema(name = "MetricsHistory", description = "Readings over a window, and where they came from")
public record MetricsHistory(Source source, int stepSeconds, List<MetricsSample> samples) {

    /** Which sink answered. */
    public enum Source {
        /** The ring buffer: every reading taken, for as long as it has been kept. */
        MEMORY,
        /** A store that outlives the process, in buckets wide enough to draw. */
        STORE,
        /** Nothing could answer: no store is configured and the window is older than memory. */
        NONE
    }
}
