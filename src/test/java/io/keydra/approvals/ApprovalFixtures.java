package io.keydra.approvals;

import io.quarkus.arc.Arc;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;

/**
 * Test-data helpers for the operations waiting on somebody.
 *
 * <p>Two things, and both are about time. A request outlives the session that raised it, so a test
 * that did not clear the table would be reading another test's requests; and the only interesting
 * thing that happens on its own happens a day later, which is not a thing a test can wait for.
 */
public final class ApprovalFixtures {

    private ApprovalFixtures() {}

    /** Empties the table so each test starts from a known state. */
    public static void deleteEveryRequest() {
        run(
                () ->
                        Panache.getSession()
                                .flatMap(
                                        session ->
                                                session.createQuery("delete from ApprovalRequest")
                                                        .executeUpdate()));
    }

    /**
     * Moves a request's expiry into the past.
     *
     * <p>Rather than shortening the configured lifetime for the whole class, which would be a
     * second application start and would race every other test in it: what is under test is what
     * expiring does, not the clock that notices.
     */
    public static void backdate(long id) {
        run(
                () ->
                        Panache.getSession()
                                .flatMap(
                                        session ->
                                                session.createQuery(
                                                                "update ApprovalRequest set"
                                                                        + " expiresAt ="
                                                                        + " :then where id = :id")
                                                        .setParameter(
                                                                "then",
                                                                java.time.Instant.now()
                                                                        .minusSeconds(60))
                                                        .setParameter("id", id)
                                                        .executeUpdate()));
    }

    /** Runs the expiry the timer would otherwise run, and answers how many it ended. */
    public static int expireNow() {
        try {
            Integer ended =
                    VertxContextSupport.subscribeAndAwait(
                            () ->
                                    Arc.container()
                                            .instance(
                                                    io.keydra.approvals.service.ApprovalService
                                                            .class)
                                            .get()
                                            .expire());
            return ended == null ? 0 : ended;
        } catch (Throwable e) {
            throw new IllegalStateException("Could not expire the unanswered requests", e);
        }
    }

    private static void run(java.util.function.Supplier<Uni<?>> work) {
        try {
            VertxContextSupport.subscribeAndAwait(() -> Panache.withTransaction(work::get));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not clear the approval requests", e);
        }
    }
}
