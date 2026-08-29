package io.keydra.common.config;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether anything has arrived through a proxy.
 *
 * <p>A fact about traffic rather than about configuration, which is why it cannot be read at
 * startup: the setting says whether Keydra believes it is behind something, and only a request can
 * say whether it is.
 *
 * <p>Recorded once and then never written again. What is being kept is a boolean about the
 * deployment, not a counter about requests — a check that wrote something on every request forever,
 * for an answer that stops changing after the first one, is the shape of check somebody eventually
 * turns off.
 */
@ApplicationScoped
public class ProxyObserved {

    /** The two ways a proxy says so, and the only two worth looking for. */
    private static final String FORWARDED = "Forwarded";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final AtomicBoolean seen = new AtomicBoolean();

    /**
     * Looks at the headers on the way past, before anything else does.
     *
     * <p>Ordered first and always calling {@code next()}: this observes and decides nothing, and a
     * fault here must not be a fault in serving the request.
     */
    void watchForProxies(@Observes Router router) {
        router.route()
                .order(Integer.MIN_VALUE)
                .handler(
                        context -> {
                            if (!seen.get()
                                    && (context.request().getHeader(FORWARDED) != null
                                            || context.request().getHeader(X_FORWARDED_FOR)
                                                    != null)) {
                                seen.set(true);
                            }
                            context.next();
                        });
    }

    /** Whether a request has ever arrived saying it came through something. */
    public boolean behindOne() {
        return seen.get();
    }
}
