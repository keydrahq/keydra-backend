package io.keydra.telemetry.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What this build sends where.
 *
 * <p>On the About page because "is this instance exporting anything" should not be answered by
 * reading a deployment manifest — which is written by somebody else, kept somewhere else, and may
 * describe a different instance from the one being asked.
 *
 * @param metricsPath where a scraper finds the numbers
 * @param traces whether traces are being collected at all
 * @param tracesTo the host they go to, and only the host: the rest of a collector's address is
 *     nobody's business, and the host is what answers "which collector"
 * @param structuredLogs whether the console is JSON rather than lines meant for a person
 */
@Schema(name = "ObservabilityDetails", description = "What this instance exports, and where")
public record ObservabilityDetails(
        String metricsPath, boolean traces, String tracesTo, boolean structuredLogs) {}
