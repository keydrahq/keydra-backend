package io.keydra.store.health;

import io.keydra.store.service.KeydraStore;
import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Whether the store this instance shares its state through is answering.
 *
 * <p>Readiness had nothing to say about it, and the reason is easy to miss: the reported "Redis
 * connection health check" belongs to the extension's own default client, which Keydra does not
 * use. Both the store and every target are raw Vert.x clients built at runtime, so neither was
 * covered. The instance could lose the store — and with it the notification fan-out between
 * instances and the cache that decides who may do what — while continuing to report itself ready
 * for traffic.
 *
 * <p>Only the store, and deliberately not the targets. A target being unreachable is an ordinary
 * Tuesday in an estate of any size and is what the connections page exists to show; making it a
 * readiness failure would take every Keydra out of its load balancer because somebody's staging
 * Redis was restarted. What readiness answers is "can this process do its job", and its job
 * includes showing you that a target is down.
 *
 * <p>An in-process store is always ready. There is nothing to reach, and saying so is more useful
 * than saying UP with no explanation.
 */
@Readiness
@ApplicationScoped
public class StoreHealthCheck implements AsyncHealthCheck {

    private static final String NAME = "Keydra store";

    /**
     * Long enough for a round trip on a busy server, short enough that a probe does not hang.
     *
     * <p>A readiness check that waits as long as the caller is willing to is a check that turns a
     * slow dependency into a slow probe, and a probe that times out at the far end reports nothing
     * at all rather than reporting a problem.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final KeydraStore store;

    @Inject
    StoreHealthCheck(KeydraStore store) {
        this.store = store;
    }

    @Override
    public Uni<HealthCheckResponse> call() {
        if (!store.isShared()) {
            return Uni.createFrom()
                    .item(
                            HealthCheckResponse.named(NAME)
                                    .up()
                                    .withData("kind", "in-process")
                                    .build());
        }
        // ping rather than get, and that difference is the whole of what this check is worth:
        // every read on the store recovers from a failure by design — a cache that cannot be
        // reached is a cache miss — so a probe built on one reported a store that had been
        // stopped as up. It did, until phase 39 went looking.
        return store.ping()
                .ifNoItem()
                .after(TIMEOUT)
                .fail()
                .map(
                        ignored ->
                                HealthCheckResponse.named(NAME)
                                        .up()
                                        .withData("kind", "shared")
                                        .build())
                .onFailure()
                .recoverWithItem(
                        unreachable ->
                                HealthCheckResponse.named(NAME)
                                        .down()
                                        .withData("kind", "shared")
                                        .withData("reason", plainest(unreachable))
                                        .build());
    }

    /** The innermost message, bounded. A probe answer is read by a machine and then by a person. */
    private static String plainest(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message =
                deepest.getMessage() == null || deepest.getMessage().isBlank()
                        ? deepest.getClass().getSimpleName()
                        : deepest.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
