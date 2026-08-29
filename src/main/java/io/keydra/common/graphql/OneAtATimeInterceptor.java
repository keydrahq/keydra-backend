package io.keydra.common.graphql;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Puts a resolver in the request's line instead of letting it start where it likes.
 *
 * <p>Outside every other application interceptor, and that is the whole of what the priority buys.
 * A resolver is not the only thing on its way to the database: the permission check reads the grant
 * tables, and auditing writes a row. At equal priority the order between two interceptors is
 * unspecified, so the permission check landed outside the queue as often as inside it — and a read
 * outside the queue is a read that can overlap the resolver queued next to it, which is the exact
 * collision this exists to stop. It showed up as {@code NoSuchElementException} from Hibernate's
 * own stack, blamed on whichever field happened to be reading at the time rather than on the check
 * that was reading beside it.
 *
 * <p>Still inside the security check, which sits at platform priority: whether the caller is anyone
 * at all is settled first, on the caller's own context, before anything joins a queue.
 *
 * <p>Only methods that return a {@code Uni} are queued. A resolver that returns a plain value never
 * touched the database asynchronously and has nothing to collide with.
 */
@OneAtATime
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 100)
public class OneAtATimeInterceptor {

    @Inject ResolverQueue queue;

    @AroundInvoke
    @SuppressWarnings("unchecked")
    Object invoke(InvocationContext context) throws Exception {
        if (!Uni.class.isAssignableFrom(context.getMethod().getReturnType())) {
            return context.proceed();
        }
        return queue.next(
                () -> {
                    try {
                        return (Uni<Object>) context.proceed();
                    } catch (Exception refused) {
                        // A resolver that threw on the way in rather than in its Uni — the queue
                        // has to see it as a finished piece of work, or the line stops here.
                        return Uni.createFrom().failure(refused);
                    }
                });
    }
}
