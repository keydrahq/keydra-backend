package io.keydra.telemetry.service;

import io.keydra.telemetry.dto.ObservabilityDetails;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What is switched on, read from the same settings that switched it on.
 *
 * <p>Read rather than remembered: a second copy of "are traces on" is a second thing that can be
 * wrong, and the interesting failure here is exactly the one where the page says yes and the
 * exporter is off.
 */
@ApplicationScoped
public class TelemetrySettings {

    private final String metricsPath;
    private final boolean tracesDisabled;
    private final Optional<String> endpoint;
    private final boolean jsonLogs;

    @Inject
    TelemetrySettings(
            @ConfigProperty(name = "quarkus.micrometer.export.prometheus.path") String metricsPath,
            @ConfigProperty(name = "quarkus.otel.sdk.disabled") boolean tracesDisabled,
            @ConfigProperty(name = "quarkus.otel.exporter.otlp.endpoint") Optional<String> endpoint,
            @ConfigProperty(name = "quarkus.log.console.json.enabled", defaultValue = "false")
                    boolean jsonLogs) {
        this.metricsPath = metricsPath;
        this.tracesDisabled = tracesDisabled;
        this.endpoint = endpoint;
        this.jsonLogs = jsonLogs;
    }

    public ObservabilityDetails describe() {
        return new ObservabilityDetails(metricsPath, !tracesDisabled, collector(), jsonLogs);
    }

    /**
     * The collector's host, or nothing when there is none.
     *
     * <p>Only the host. A collector address can carry a path and, in some deployments, a token in
     * it; the question being answered is "which collector", and the host answers it.
     */
    private String collector() {
        if (tracesDisabled) {
            return null;
        }
        return endpoint.filter(named -> !named.isBlank())
                .map(
                        named -> {
                            try {
                                URI parsed = URI.create(named);
                                return parsed.getHost() == null ? null : parsed.getHost();
                            } catch (IllegalArgumentException notAUri) {
                                return null;
                            }
                        })
                .orElse(null);
    }
}
