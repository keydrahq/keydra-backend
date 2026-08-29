package io.keydra.common.graphql;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.RequestScoped;
import java.util.function.Supplier;

/**
 * One request's resolvers, in a line.
 *
 * <p>Request scoped, so the line is per request and two people asking at once are never each
 * other's problem. Each piece of work starts when the one before it has finished, whichever way it
 * finished: a resolver that failed still has to let go of the session, and a queue that stopped at
 * the first failure would hang every remaining field of that query.
 *
 * <p>The chain is memoized because two things subscribe to it — graphql-java, for the answer, and
 * the next resolver in line, for the go-ahead — and work that ran twice would be a second read for
 * every field after the first.
 */
@RequestScoped
public class ResolverQueue {

    private Uni<?> tail = Uni.createFrom().voidItem();

    /** Runs the work after everything already queued, and hands back what it produces. */
    public synchronized <T> Uni<T> next(Supplier<Uni<T>> work) {
        Uni<T> queued =
                tail.onItemOrFailure()
                        .transformToUni((ignored, alsoIgnored) -> work.get())
                        .memoize()
                        .indefinitely();
        tail =
                queued.onItemOrFailure()
                        .transformToUni((ignored, alsoIgnored) -> Uni.createFrom().voidItem());
        return queued;
    }
}
