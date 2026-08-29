package io.keydra.authz.service;

import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Clears what was worked out about access, after something changed it.
 *
 * <p>After rather than before, and only on success: a transaction that rolled back changed nothing,
 * and clearing a cache for it would mean re-reading the database to arrive at the same answer.
 *
 * <p>Runs outside the transaction interceptor, which is what the priority buys. Clearing while the
 * write is still uncommitted would leave a window in which another instance re-reads the old row
 * and caches it again — the one ordering mistake this could make, and the one that would look like
 * a revocation that did not take.
 */
@ChangesAccess
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 10)
public class AccessChangeInterceptor {

    @Inject AuthzCache cache;

    @Inject io.keydra.events.service.SocketAudience audience;

    @AroundInvoke
    Object invoke(InvocationContext context) throws Exception {
        boolean writes = context.getMethod().isAnnotationPresent(WithTransaction.class);
        Object answer = context.proceed();
        if (!writes || !(answer instanceof Uni<?> result)) {
            return answer;
        }
        return result.call(
                ignored ->
                        cache.forgetEverything()
                                // The permission cache is not the only place an answer about who
                                // may see what is held. An open socket carries the set of targets
                                // it may hear about, worked out when it opened; closing them makes
                                // every browser reconnect and ask again. Phase 9 decided a
                                // revocation takes effect on the next request, and a socket that
                                // kept its old answer would quietly turn that into "tomorrow".
                                .invoke(audience::refreshAll));
    }
}
