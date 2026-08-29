package io.keydra.common.vertx;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.function.Supplier;

/**
 * Runs blocking work away from the event loop, and comes back to the context it started on.
 *
 * <p>The first half of that is the obvious one: a client with no reactive form — Aerospike's info
 * command, everything TiKV's raw client offers — cannot be called on an event loop, so it is moved
 * to a worker thread. The second half is the one that is easy to forget and fails much later. A
 * {@code runSubscriptionOn} moves the subscription *and* the emission, so everything chained after
 * it also runs on the worker thread; and what is chained after a resource method in this
 * application is Hibernate Reactive — the audit entry an {@code @Audited} method writes, a status
 * being recorded, whatever the caller does next. Hibernate Reactive runs on a Vert.x context and
 * refuses to run anywhere else, so the failure reads {@code HR000068: This method should
 * exclusively be invoked from a Vert.x EventLoop thread}.
 *
 * <p>So the caller's context is captured when the work is subscribed to and the result is emitted
 * back onto it. The context is captured at subscription rather than at assembly because a chain
 * built in one place is often subscribed to in another, and it is the subscriber's context the rest
 * of the chain belongs to.
 *
 * <p>Off a Vert.x context entirely — a test, a plain thread — there is nothing to return to and the
 * work simply runs on the worker pool.
 */
public final class OffLoop {

    private OffLoop() {}

    /**
     * Runs one blocking call on the worker pool and emits its answer on the caller's context.
     *
     * @param work the blocking call; its failure is passed on as the Uni's failure
     */
    public static <T> Uni<T> call(Supplier<T> work) {
        return Uni.createFrom()
                .deferred(
                        () -> {
                            Context caller = Vertx.currentContext();
                            Uni<T> off =
                                    Uni.createFrom()
                                            .item(work)
                                            .runSubscriptionOn(
                                                    Infrastructure.getDefaultWorkerPool());
                            return caller == null
                                    ? off
                                    : off.emitOn(
                                            command ->
                                                    caller.runOnContext(ignored -> command.run()));
                        });
    }
}
