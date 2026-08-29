package io.keydra.authz.service;

import io.keydra.authz.dto.ProviderDtos.GroupMappingRequest;
import io.keydra.authz.dto.ProviderDtos.GroupMappingSummary;
import io.keydra.authz.dto.ProviderDtos.ProviderRequest;
import io.keydra.authz.dto.ProviderDtos.ProviderSummary;
import io.keydra.authz.dto.ProviderDtos.SignInOption;
import io.keydra.authz.entity.IdentityProviderConfig;
import io.keydra.authz.entity.ProviderGroupMapping;
import io.keydra.authz.entity.ProviderKind;
import io.keydra.authz.exception.AuthzConflictException;
import io.keydra.authz.persistence.AuthzRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adding and changing the places people can sign in from.
 *
 * <p>Discovery runs here, when a provider is saved, rather than at sign-in. Saving is where
 * somebody is waiting for an answer and able to act on it; a sign-in is not, and a discovery
 * document fetched on every one would make every sign-in depend on the provider's slowest morning.
 */
@ApplicationScoped
@ChangesAccess
public class ProviderAdminService {

    private final AuthzRepository repository;
    private final ProviderDiscovery discovery;

    @Inject
    ProviderAdminService(AuthzRepository repository, ProviderDiscovery discovery) {
        this.repository = repository;
        this.discovery = discovery;
    }

    /** What the login page offers, which is only what is switched on. */
    @WithSession
    public Uni<List<SignInOption>> options() {
        return repository
                .allProviders()
                .map(
                        providers ->
                                providers.stream()
                                        .filter(provider -> provider.enabled)
                                        .filter(ProviderAdminService::usable)
                                        .map(
                                                provider ->
                                                        new SignInOption(
                                                                provider.key,
                                                                provider.displayName,
                                                                provider.kind))
                                        .toList());
    }

    /**
     * Whether a provider could actually take somebody through the flow.
     *
     * <p>A button that leads nowhere is worse than no button: the person who presses it has no way
     * of knowing the fault is not theirs.
     */
    private static boolean usable(IdentityProviderConfig provider) {
        return provider.authorizationEndpoint != null && provider.tokenEndpoint != null;
    }

    @WithSession
    public Uni<List<ProviderSummary>> providers(String publicUrl) {
        return repository
                .allProviders()
                .flatMap(
                        providers ->
                                mappingsByProvider(providers)
                                        .flatMap(
                                                mappings ->
                                                        groupNames()
                                                                .map(
                                                                        names ->
                                                                                describe(
                                                                                        providers,
                                                                                        mappings,
                                                                                        names,
                                                                                        publicUrl))));
    }

    @WithTransaction
    public Uni<ProviderSummary> create(ProviderRequest request, String publicUrl) {
        return repository
                .providerByKey(request.key())
                .flatMap(
                        existing -> {
                            if (existing != null) {
                                return Uni.createFrom()
                                        .failure(
                                                new AuthzConflictException(
                                                        "A provider keyed "
                                                                + request.key()
                                                                + " already exists"));
                            }
                            IdentityProviderConfig provider = new IdentityProviderConfig();
                            provider.key = request.key();
                            apply(provider, request);
                            return resolveEndpoints(provider)
                                    .flatMap(ignored -> repository.save(provider))
                                    .map(saved -> toSummary(saved, List.of(), Map.of(), publicUrl));
                        });
    }

    @WithTransaction
    public Uni<ProviderSummary> update(Long id, ProviderRequest request, String publicUrl) {
        return repository
                .provider(id)
                .flatMap(
                        provider -> {
                            if (provider == null) {
                                return Uni.createFrom()
                                        .failure(new AuthzConflictException("No such provider"));
                            }
                            apply(provider, request);
                            return resolveEndpoints(provider)
                                    .flatMap(ignored -> repository.mappingsOf(id))
                                    .flatMap(
                                            mappings ->
                                                    groupNames()
                                                            .map(
                                                                    names ->
                                                                            toSummary(
                                                                                    provider,
                                                                                    mappings, names,
                                                                                    publicUrl)));
                        });
    }

    @WithTransaction
    public Uni<Boolean> delete(Long id) {
        return repository.deleteProvider(id);
    }

    @WithTransaction
    public Uni<Void> addMapping(Long providerId, GroupMappingRequest request) {
        ProviderGroupMapping mapping = new ProviderGroupMapping();
        mapping.providerId = providerId;
        mapping.claimValue = request.claimValue();
        mapping.groupId = request.groupId();
        return repository.save(mapping).replaceWithVoid();
    }

    @WithTransaction
    public Uni<Boolean> removeMapping(Long mappingId) {
        return repository.deleteMapping(mappingId);
    }

    /**
     * Fetches the discovery document, for an OIDC provider that named an issuer.
     *
     * <p>An OAuth 2 provider has no document to fetch, so its endpoints are whatever was typed in.
     * A failure here fails the save, deliberately: a provider stored with no endpoints is a button
     * on the login page that does nothing, and finding out at configuration time is the whole
     * reason this happens at configuration time.
     */
    private Uni<Void> resolveEndpoints(IdentityProviderConfig provider) {
        boolean shouldDiscover =
                provider.kind == ProviderKind.OIDC
                        && provider.issuer != null
                        && !provider.issuer.isBlank();
        if (!shouldDiscover) {
            return Uni.createFrom().voidItem();
        }
        return discovery
                .discover(provider.issuer)
                .invoke(
                        endpoints -> {
                            provider.authorizationEndpoint = endpoints.authorization();
                            provider.tokenEndpoint = endpoints.token();
                            provider.userInfoEndpoint = endpoints.userInfo();
                        })
                .replaceWithVoid();
    }

    /** An absent secret keeps the stored one; the API never returns one to prefill a form with. */
    private static void apply(IdentityProviderConfig provider, ProviderRequest request) {
        provider.displayName = request.displayName();
        provider.kind = request.kind();
        provider.enabled = request.enabled() == null || request.enabled();
        provider.sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        provider.issuer = blankToNull(request.issuer());
        provider.clientId = request.clientId();
        if (request.clientSecret() != null && !request.clientSecret().isBlank()) {
            provider.clientSecret = request.clientSecret();
        }
        provider.scopes = orDefault(request.scopes(), "openid profile email");
        provider.authorizationEndpoint = blankToNull(request.authorizationEndpoint());
        provider.tokenEndpoint = blankToNull(request.tokenEndpoint());
        provider.userInfoEndpoint = blankToNull(request.userInfoEndpoint());
        provider.subjectClaim = orDefault(request.subjectClaim(), "sub");
        provider.usernameClaim = orDefault(request.usernameClaim(), "preferred_username");
        provider.emailClaim = blankToNull(request.emailClaim());
        provider.nameClaim = blankToNull(request.nameClaim());
        provider.groupsClaim = blankToNull(request.groupsClaim());
        provider.autoCreateUsers = request.autoCreateUsers() == null || request.autoCreateUsers();
    }

    private Uni<Map<Long, List<ProviderGroupMapping>>> mappingsByProvider(
            List<IdentityProviderConfig> providers) {
        Uni<Map<Long, List<ProviderGroupMapping>>> collected =
                Uni.createFrom().item(new HashMap<>());
        for (IdentityProviderConfig provider : providers) {
            Long id = provider.id;
            collected =
                    collected.flatMap(
                            soFar ->
                                    repository
                                            .mappingsOf(id)
                                            .map(
                                                    mappings -> {
                                                        soFar.put(id, mappings);
                                                        return soFar;
                                                    }));
        }
        return collected;
    }

    private Uni<Map<Long, String>> groupNames() {
        return repository
                .allGroups()
                .map(
                        groups -> {
                            Map<Long, String> names = new HashMap<>();
                            groups.forEach(group -> names.put(group.id, group.name));
                            return names;
                        });
    }

    private static List<ProviderSummary> describe(
            List<IdentityProviderConfig> providers,
            Map<Long, List<ProviderGroupMapping>> mappings,
            Map<Long, String> groupNames,
            String publicUrl) {
        return providers.stream()
                .map(
                        provider ->
                                toSummary(
                                        provider,
                                        mappings.getOrDefault(provider.id, List.of()),
                                        groupNames,
                                        publicUrl))
                .toList();
    }

    private static ProviderSummary toSummary(
            IdentityProviderConfig provider,
            List<ProviderGroupMapping> mappings,
            Map<Long, String> groupNames,
            String publicUrl) {
        return new ProviderSummary(
                provider.id,
                provider.key,
                provider.displayName,
                provider.kind,
                provider.enabled,
                provider.sortOrder,
                provider.issuer,
                provider.clientId,
                provider.clientSecret != null && !provider.clientSecret.isBlank(),
                provider.scopes,
                provider.authorizationEndpoint,
                provider.tokenEndpoint,
                provider.userInfoEndpoint,
                usable(provider),
                provider.subjectClaim,
                provider.usernameClaim,
                provider.emailClaim,
                provider.nameClaim,
                provider.groupsClaim,
                provider.autoCreateUsers,
                // Shown so it can be copied into the provider's own configuration, which is
                // the one thing that has to match exactly and the usual reason a first attempt
                // is refused.
                redirectUri(publicUrl, provider.key),
                mappings.stream()
                        .map(
                                mapping ->
                                        new GroupMappingSummary(
                                                mapping.id,
                                                mapping.claimValue,
                                                mapping.groupId,
                                                groupNames.getOrDefault(mapping.groupId, "?")))
                        .toList());
    }

    /** Where a provider sends people back to. One shape, so it is predictable. */
    public static String redirectUri(String publicUrl, String key) {
        return publicUrl.replaceAll("/+$", "") + "/api/v1/auth/providers/" + key + "/callback";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
