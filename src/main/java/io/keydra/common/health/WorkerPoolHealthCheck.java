package io.keydra.common.health;

import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Whether work that has to leave the event loop can still be run.
 *
 * <p>Liveness had no checks at all, and that is more defensible than it looks: this is a reactive
 * application, so a blocked event loop cannot answer an HTTP request — the probe getting any answer
 * already proves the loop is turning. Adding a check that repeated that would be theatre.
 *
 * <p>What it would not prove is this one. Everything slow here runs on the worker pool: Argon2 on
 * every sign-in, the name resolution the egress guard does, the SSH handshake a tunnel test opens.
 * A pool exhausted by those leaves the event loop perfectly healthy and every one of them hanging
 * for ever — an instance answering probes and signing nobody in. This submits one trivial task and
 * requires it to come back.
 *
 * <p>Liveness rather than readiness, and nothing but the pool. A liveness check that touched a
 * database would restart every instance during a database blip, which turns a recoverable outage
 * into an outage plus a restart storm; that is what readiness is for. The rule is that liveness
 * asks about this process and nothing outside it.
 */
@Liveness
@ApplicationScoped
public class WorkerPoolHealthCheck implements AsyncHealthCheck {

    private static final String NAME = "Worker pool";

    /**
     * Generous on purpose.
     *
     * <p>A pool that is merely busy will run a trivial task well inside this; a pool that cannot
     * run one in five seconds is not busy, it is stuck. The cost of being wrong here is a restarted
     * instance, so the threshold is set where being wrong is unlikely rather than where a problem
     * is noticed soonest.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Override
    public Uni<HealthCheckResponse> call() {
        return Uni.createFrom()
                .item(() -> Boolean.TRUE)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .ifNoItem()
                .after(TIMEOUT)
                .fail()
                .map(ignored -> HealthCheckResponse.named(NAME).up().build())
                .onFailure()
                .recoverWithItem(
                        stuck ->
                                HealthCheckResponse.named(NAME)
                                        .down()
                                        .withData(
                                                "reason",
                                                "No worker thread ran a task within "
                                                        + TIMEOUT.toSeconds()
                                                        + "s")
                                        .build());
    }
}
