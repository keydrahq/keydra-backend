package io.keydra.common.web;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;

/**
 * Serves the application shell for paths that belong to the browser's router.
 *
 * <p>The frontend routes on the URL, so {@code /connections/3/keys} is a real address a person can
 * bookmark, share and reload. To this server it is not a file, and without this it answers 404 —
 * which makes every deep link work exactly until somebody presses refresh.
 *
 * <p>Only in the packaged image does this matter: in development the Vite server does the same
 * thing for the same reason.
 */
@ApplicationScoped
public class SinglePageRoutes {

    /**
     * Prefixes that belong to the server, not the browser.
     *
     * <p>A path under one of these that does not exist is genuinely a 404, and answering it with a
     * page of HTML would turn a missing endpoint into a parse error in whatever called it.
     */
    private static final List<String> SERVER_PATHS = List.of("/api/", "/q/", "/assets/");

    /** Runs after the static handler, so a real file is still served as itself. */
    void registerFallback(@Observes Router router) {
        router.route()
                .order(Integer.MAX_VALUE - 1)
                .handler(
                        context -> {
                            if (shouldServeShell(context)) {
                                context.reroute("/index.html");
                            } else {
                                context.next();
                            }
                        });
    }

    private static boolean shouldServeShell(RoutingContext context) {
        if (context.request().method() != HttpMethod.GET) {
            return false;
        }
        String path = context.normalizedPath();
        if (SERVER_PATHS.stream().anyMatch(path::startsWith)) {
            return false;
        }
        // Only for a browser asking for a page. An XHR or a fetch that missed should be
        // told it missed, not handed markup.
        String accept = context.request().getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}
