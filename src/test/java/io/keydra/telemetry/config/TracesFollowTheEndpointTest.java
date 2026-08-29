package io.keydra.telemetry.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * One setting, not two.
 *
 * <p>A plain unit test, because the class exists precisely to run before anything else does: it is
 * read by the OpenTelemetry extension long before a bean container exists, so there is nothing here
 * to start.
 */
class TracesFollowTheEndpointTest {

    @Test
    void namingACollectorTurnsTracingOn() {
        TracesFollowTheEndpoint source =
                new TracesFollowTheEndpoint(() -> "http://collector.internal:4317");

        assertEquals("false", source.getValue(TracesFollowTheEndpoint.DISABLED));
        assertEquals(Set.of(TracesFollowTheEndpoint.DISABLED), source.getPropertyNames());
    }

    @Test
    void namingNothingSaysNothing() {
        // Not "true": saying nothing leaves the application's own default in place, which is
        // what makes an operator able to override this in either direction.
        assertNull(
                new TracesFollowTheEndpoint(() -> null).getValue(TracesFollowTheEndpoint.DISABLED));
        assertNull(
                new TracesFollowTheEndpoint(() -> "  ").getValue(TracesFollowTheEndpoint.DISABLED));
        assertTrue(new TracesFollowTheEndpoint(() -> "").getPropertyNames().isEmpty());
    }

    @Test
    void sitsAboveTheApplicationsOwnSettingsAndBelowTheEnvironments() {
        // 250 is application.properties and 300 is the environment. Between them is the only
        // place this can be: it has to beat the default and lose to somebody who insists.
        int ordinal = new TracesFollowTheEndpoint(() -> "http://collector:4317").getOrdinal();
        assertTrue(ordinal > 250 && ordinal < 300, "Ordinal was " + ordinal);
    }
}
