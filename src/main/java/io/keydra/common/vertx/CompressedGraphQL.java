package io.keydra.common.vertx;

import io.quarkus.vertx.http.runtime.HttpCompressionHandler;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Sends GraphQL answers compressed, like every other answer.
 *
 * <p>{@code quarkus.http.enable-compression} covers what Quarkus itself routes — the REST API, the
 * built frontend — because the compression is installed per route as those routes are registered.
 * GraphQL arrives on a route the extension registers for itself, so it was the one surface
 * answering in full. It is also the surface where it matters most: a page asks one wide question
 * instead of several narrow ones, so the single answer is the largest thing Keydra sends, and it is
 * repetitive JSON — the same field names once per row, the same handful of state words, timestamps
 * sharing their first sixteen characters.
 *
 * <p>A filter rather than a copy of the handler: {@code compressIfNeeded} is the same call Quarkus
 * makes on its own routes, given the same media types read from the same property, so the two
 * cannot come to disagree about what gets compressed.
 *
 * <p>Ordered above the routes it protects and below authentication, and it only ever adds a header
 * — a request that is about to be refused is refused exactly as before.
 */
@ApplicationScoped
public class CompressedGraphQL {

    /** Ahead of the extension's own route, which is what makes it possible to wrap the answer. */
    private static final int BEFORE_THE_ROUTE = 1000;

    private final boolean enabled;
    private final Set<String> mediaTypes;
    private final String path;

    CompressedGraphQL(
            @ConfigProperty(name = "quarkus.http.enable-compression", defaultValue = "false")
                    boolean enabled,
            @ConfigProperty(name = "quarkus.http.compress-media-types")
                    Optional<List<String>> mediaTypes,
            @ConfigProperty(name = "quarkus.smallrye-graphql.root-path", defaultValue = "/graphql")
                    String path) {
        this.enabled = enabled;
        this.mediaTypes = Set.copyOf(mediaTypes.orElseGet(List::of));
        // The property is written without a leading slash — "graphql", not "/graphql" — and a
        // request's path always has one. Comparing them as they come matches nothing, silently,
        // which is a filter that runs on every request and does nothing on any of them.
        this.path = path.startsWith("/") ? path : "/" + path;
    }

    void install(@Observes Filters filters) {
        if (!enabled || mediaTypes.isEmpty()) {
            return;
        }
        filters.register(this::compress, BEFORE_THE_ROUTE);
    }

    private void compress(RoutingContext context) {
        // The prefix rather than an exact match: the schema is served from underneath the same
        // root, and a schema is the largest plain text here by some distance.
        if (context.normalizedPath().startsWith(path)) {
            // Just before the headers go out rather than here. What decides whether an answer is
            // compressed is its content type, and at filter time nothing has produced one yet:
            // asking now reads null and refuses everything. Quarkus's own routes get away with
            // asking early because they declare what they produce when the route is built, and a
            // route registered by an extension declares nothing to us.
            context.addHeadersEndHandler(
                    ignored -> HttpCompressionHandler.compressIfNeeded(context, mediaTypes));
        }
        context.next();
    }
}
