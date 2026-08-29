package io.keydra.cluster.health;

import io.keydra.cluster.service.Draining;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Whether this instance should be sent new browsers.
 *
 * <p>The whole mechanism by which a drain takes effect, and deliberately somebody else's: an
 * unready pod leaves the service's endpoints, a load balancer with a health check stops choosing
 * it, and neither needed Keydra to invent anything. What is already open stays open, which is the
 * point — a drain that cut twelve sockets would be a restart with extra steps.
 *
 * <p>Readiness and not liveness. A draining instance is doing exactly what it was told; reporting
 * it unhealthy to the thing that restarts pods would make draining a way of getting killed, which
 * is the operator's decision to make rather than this one's.
 *
 * <p>Synchronous, and the only check here that is: it reads a boolean the beat already fetched.
 * Asking the database on every probe would put the readiness of an instance at the mercy of a round
 * trip taken several times a second, which is how a database blip empties a fleet.
 */
@Readiness
@ApplicationScoped
public class DrainingHealthCheck implements HealthCheck {

    private static final String NAME = "Accepting traffic";

    private final Draining draining;

    @Inject
    DrainingHealthCheck(Draining draining) {
        this.draining = draining;
    }

    @Override
    public HealthCheckResponse call() {
        if (!draining.underWay()) {
            return HealthCheckResponse.named(NAME).up().build();
        }
        // Said in the response rather than only in a log, because whoever is looking at a probe
        // that fails needs to know this one was asked for.
        return HealthCheckResponse.named(NAME).down().withData("state", "draining").build();
    }
}
