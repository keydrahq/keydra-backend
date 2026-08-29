package io.keydra.security;

import io.keydra.security.persistence.AuditRepository;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;

/**
 * Test-data helper for the audit log.
 *
 * <p>The log is deliberately append-only in the application — nothing there deletes from it — so
 * clearing it between tests is something only a test needs, and the helper lives with the tests.
 */
public final class AuditFixtures {

    private AuditFixtures() {}

    public static void deleteAllEvents() {
        AuditRepository repository = Arc.container().instance(AuditRepository.class).get();
        try {
            VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(repository::deleteAll));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not clear audit events", e);
        }
    }
}
