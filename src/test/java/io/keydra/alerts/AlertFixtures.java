package io.keydra.alerts;

import io.keydra.alerts.service.AlertWatches;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;

/**
 * Leaves the rules, their history and what is being watched empty.
 *
 * <p>All three, and the third is the one that bites. Deleting rows without telling the watches
 * leaves the registry holding rules whose targets the next test has deleted — and, worse, leaves
 * the sampler running against them for the rest of the suite.
 */
public final class AlertFixtures {

    private AlertFixtures() {}

    public static void deleteEveryRule() {
        AlertWatches watches = Arc.container().instance(AlertWatches.class).get();
        try {
            VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(AlertFixtures::clear));
            // Reloads from a table that is now empty, which also releases every watch.
            VertxContextSupport.subscribeAndAwait(watches::reload);
            // And once more, for what an evaluation already in flight wrote in between. Until
            // the reload the sampler is still watching the rules that were just deleted, so a
            // tick landing in that window leaves an event behind — a row belonging to a rule
            // that no longer exists, in a table the next test is about to make assertions on.
            // The window is microseconds rather than nothing, which is why the tests about
            // counts also ask about their own rule rather than about the whole table.
            VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(() -> delete("delete from AlertEvent")));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not clear the alert rules", failure);
        }
    }

    private static Uni<Integer> clear() {
        return delete("delete from AlertEvent")
                .flatMap(ignored -> delete("delete from AlertRule"))
                .flatMap(ignored -> delete("delete from AlertDelivery"));
    }

    private static Uni<Integer> delete(String query) {
        return Panache.getSession().flatMap(session -> session.createQuery(query).executeUpdate());
    }
}
