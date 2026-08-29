package io.keydra.common.vertx;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Both halves of what {@link OffLoop} is for, because getting either wrong is a bug that only shows
 * up somewhere else.
 *
 * <p>Running the blocking call on the event loop blocks every request the loop is serving. Emitting
 * its answer on the worker thread means everything chained after it — the audit entry an
 * {@code @Audited} resource writes, most of all — runs where Hibernate Reactive refuses to run, and
 * reads as {@code HR000068} in a stack trace with no application frame in it. That is exactly how
 * this class came to exist.
 */
class OffLoopTest {

    private Vertx vertx;

    @BeforeEach
    void start() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void stop() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void runsTheWorkOffTheEventLoopAndAnswersOnIt() throws Exception {
        Context caller = VertxContext.getOrCreateDuplicatedContext(vertx);
        CompletableFuture<String[]> answer = new CompletableFuture<>();

        caller.runOnContext(
                ignored ->
                        OffLoop.call(() -> Thread.currentThread().getName())
                                .subscribe()
                                .with(
                                        ranOn ->
                                                answer.complete(
                                                        new String[] {
                                                            ranOn,
                                                            String.valueOf(
                                                                    Vertx.currentContext()
                                                                            == caller)
                                                        }),
                                        answer::completeExceptionally));

        String[] where = answer.get(10, TimeUnit.SECONDS);
        assertThat(where[0], not(startsWith("vert.x-eventloop")));
        assertThat(where[1], is("true"));
    }

    @Test
    void bringsAFailureBackToTheContextToo() throws Exception {
        Context caller = VertxContext.getOrCreateDuplicatedContext(vertx);
        CompletableFuture<String> onContext = new CompletableFuture<>();

        caller.runOnContext(
                ignored ->
                        OffLoop.<String>call(
                                        () -> {
                                            throw new IllegalStateException("no");
                                        })
                                .subscribe()
                                .with(
                                        item -> onContext.complete("emitted an item"),
                                        failure ->
                                                onContext.complete(
                                                        failure.getMessage()
                                                                + "|"
                                                                + (Vertx.currentContext()
                                                                        == caller))));

        assertThat(onContext.get(10, TimeUnit.SECONDS), is("no|true"));
    }

    /** Off a Vert.x context there is nothing to come back to, and the work still runs. */
    @Test
    void worksWithNoContextAtAll() {
        assertThat(
                OffLoop.call(() -> "answered").await().atMost(java.time.Duration.ofSeconds(10)),
                is("answered"));
    }
}
