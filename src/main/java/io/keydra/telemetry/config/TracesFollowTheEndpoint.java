package io.keydra.telemetry.config;

import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Turns tracing on because somebody named a collector, rather than because they set a second flag.
 *
 * <p>Two settings that have to agree is a configuration mistake waiting to be made in both
 * directions: an endpoint with the SDK off collects nothing and says nothing about why, and the SDK
 * on with no endpoint writes an export failure to the log every few seconds, which is worse than no
 * tracing at all. There is only one thing an operator actually decides — where the traces go — so
 * that is the only thing they set.
 *
 * <p>A config source rather than code that reads a flag, because the flag is read by the
 * OpenTelemetry extension long before any of Keydra's own beans exist. The ordinal sits above the
 * application's own properties and below the environment's, so this overrides the default of "off"
 * and an operator who really wants it off can still say so with {@code
 * QUARKUS_OTEL_SDK_DISABLED=true}.
 */
public class TracesFollowTheEndpoint implements ConfigSource {

    static final String DISABLED = "quarkus.otel.sdk.disabled";

    /** Above application.properties (250) and below environment variables (300). */
    private static final int ORDINAL = 275;

    private final Supplier<String> endpoint;

    public TracesFollowTheEndpoint() {
        this(() -> System.getenv("KEYDRA_OTLP_ENDPOINT"));
    }

    /** For a test, which cannot set an environment variable on a running process. */
    TracesFollowTheEndpoint(Supplier<String> endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public Set<String> getPropertyNames() {
        return collecting() ? Set.of(DISABLED) : Set.of();
    }

    @Override
    public String getValue(String name) {
        return collecting() && DISABLED.equals(name) ? "false" : null;
    }

    @Override
    public String getName() {
        return "keydra-traces";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    private boolean collecting() {
        String named = endpoint.get();
        return named != null && !named.isBlank();
    }
}
