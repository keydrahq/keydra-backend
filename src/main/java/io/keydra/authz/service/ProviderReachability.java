package io.keydra.authz.service;

import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.common.reach.Reachable;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Whether the identity providers answer.
 *
 * <p>The check is a fetch of the issuer's discovery document, which is the same request a sign-in
 * makes first and which somebody's server answers a thousand times an hour. It goes through {@link
 * ProviderDiscovery}, and therefore through {@code EgressGuard}, rather than through a second
 * implementation — a second one would be a second thing to keep right about an address somebody
 * typed.
 *
 * <p>A provider configured with endpoints and no issuer is not checked and is not counted against
 * health. There is nothing to fetch: somebody filled the endpoints in by hand, which is the
 * supported way to configure a provider that publishes no discovery document, and reporting it as
 * unreachable would be reporting the absence of a document nobody said existed.
 */
@ApplicationScoped
public class ProviderReachability implements Reachable {

    /** Stable: half of the key every answer is stored under. */
    public static final String KIND = "identity-provider";

    private final AuthzRepository repository;
    private final ProviderDiscovery discovery;

    @Inject
    ProviderReachability(AuthzRepository repository, ProviderDiscovery discovery) {
        this.repository = repository;
        this.discovery = discovery;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describedAs() {
        return "identity provider";
    }

    @Override
    @WithSession
    public Uni<List<Subject>> subjects() {
        return repository
                .allProviders()
                .map(
                        providers ->
                                providers.stream()
                                        .filter(
                                                provider ->
                                                        provider.issuer != null
                                                                && !provider.issuer.isBlank())
                                        .map(
                                                provider ->
                                                        new Subject(
                                                                provider.id,
                                                                provider.displayName,
                                                                provider.enabled))
                                        .toList());
    }

    @Override
    public Uni<Outcome> check(Long id) {
        // The row is read in a session and the fetch happens outside one: a discovery document
        // takes as long as somebody else's server makes it take, and a session held across that
        // is a database connection held across it.
        return repository
                .providerForUse(id)
                .flatMap(
                        provider -> {
                            if (provider == null
                                    || provider.issuer == null
                                    || provider.issuer.isBlank()) {
                                return Uni.createFrom().item(Outcome.not("No issuer to ask"));
                            }
                            return discovery
                                    .discover(provider.issuer)
                                    .map(endpoints -> Outcome.fine())
                                    .onFailure()
                                    // Never fails its caller: "it did not answer, and here is what
                                    // it said" is the answer being asked for, and a failure would
                                    // stop the walk at the first thing that is down — which is the
                                    // moment the page is most worth reading.
                                    .recoverWithItem(failure -> Outcome.not(plainest(failure)));
                        });
    }

    /** The innermost message, which is the useful half of what a client wraps a refusal in. */
    private static String plainest(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
    }
}
