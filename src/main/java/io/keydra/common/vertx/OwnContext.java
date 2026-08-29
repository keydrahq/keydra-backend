package io.keydra.common.vertx;

import io.quarkus.vertx.core.runtime.context.VertxContextSafetyToggle;
import io.smallrye.common.vertx.VertxContext;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs work on a Vert.x context of its own.
 *
 * <p>For the work that starts from a timer rather than from a request. Everything reactive here
 * ends in Hibernate, which runs on a duplicated context and nowhere else — and joining whatever
 * context a timer happens to be on means joining a session that a finished request has closed. That
 * failure reads as "Session/EntityManager is closed" and arrives only under load, which is the
 * worst way to learn it.
 *
 * <p>A fresh duplicated context rather than a duplicate of the current one, so nothing is
 * inherited: what this is for is a session of the work's own.
 */
public final class OwnContext {

    private OwnContext() {}

    /**
     * Runs the work on a context of its own and answers what it produced.
     *
     * <p>The waiting form of {@link #run}, for the caller that needs the answer rather than only
     * needing the work done. The case it exists for is authentication: an identity provider runs
     * outside any request context and finishes on a worker thread, so anything it does with
     * Hibernate Reactive afterwards has no context to be on — and unlike a history write, whether a
     * second factor was accepted is not something a sign-in can be handed back without.
     */
    public static <T> Uni<T> call(Vertx vertx, Supplier<Uni<T>> work) {
        Context current = Vertx.currentContext();
        Context own =
                current == null
                        ? VertxContext.getOrCreateDuplicatedContext(vertx)
                        : VertxContext.createNewDuplicatedContext(current);
        VertxContextSafetyToggle.setContextSafe(own, true);
        return Uni.createFrom()
                .emitter(
                        emitter ->
                                own.runOnContext(
                                        ignored ->
                                                work.get()
                                                        .subscribe()
                                                        .with(emitter::complete, emitter::fail)));
    }

    /** Subscribes to the work on a context of its own; returns immediately. */
    public static <T> void run(Vertx vertx, Supplier<Uni<T>> work, Consumer<Throwable> onFailure) {
        Context current = Vertx.currentContext();
        Context own =
                current == null
                        ? VertxContext.getOrCreateDuplicatedContext(vertx)
                        : VertxContext.createNewDuplicatedContext(current);
        VertxContextSafetyToggle.setContextSafe(own, true);
        own.runOnContext(ignored -> work.get().subscribe().with(done -> {}, onFailure::accept));
    }
}
