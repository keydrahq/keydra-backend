package io.keydra.authz.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.ProviderDtos.GroupMappingRequest;
import io.keydra.authz.dto.ProviderDtos.ProviderRequest;
import io.keydra.authz.dto.ProviderDtos.ProviderSummary;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.ProviderAdminService;
import io.keydra.authz.service.PublicUrl;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.Roles;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Where sign-ins can come from other than here.
 *
 * <p>Separate from the rest of access because it needs something they do not: the address a browser
 * reaches this instance at, which goes into the redirect URI a provider is told to send people back
 * to. A provider refuses a sign-in when that address is off by a character, and the address Keydra
 * sees is often not the one anybody types — a proxy terminates TLS elsewhere, and in development a
 * Vite server on another port forwards to here.
 *
 * <p>Taken from the Vert.x request rather than from a JAX-RS UriInfo, because this surface has no
 * JAX-RS anything. Same derivation, same property override.
 */
@GraphQLApi
@OneAtATime
public class ProviderQueries {

    private final ProviderAdminService service;
    private final PublicUrl publicUrl;
    private final CurrentVertxRequest request;

    @Inject
    ProviderQueries(
            ProviderAdminService service, PublicUrl publicUrl, CurrentVertxRequest request) {
        this.service = service;
        this.publicUrl = publicUrl;
        this.request = request;
    }

    private String reachedAt() {
        return publicUrl.of(request.getCurrent().request());
    }

    @Query("identityProviders")
    @Description("Every provider, with the redirect URI each one has to be told about")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.IDP_MANAGE)
    public Uni<List<ProviderSummary>> identityProviders() {
        return service.providers(reachedAt());
    }

    @Mutation("createIdentityProvider")
    @Description("Adds a provider people can sign in through")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.IDP_MANAGE)
    public Uni<ProviderSummary> createIdentityProvider(
            @Name("provider") @Valid ProviderRequest provider) {
        return service.create(provider, reachedAt());
    }

    @Mutation("updateIdentityProvider")
    @Description("Changes a provider")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.IDP_MANAGE)
    public Uni<ProviderSummary> updateIdentityProvider(
            @Name("id") Long id, @Name("provider") @Valid ProviderRequest provider) {
        return service.update(id, provider, reachedAt());
    }

    @Mutation("deleteIdentityProvider")
    @Description("Removes a provider; accounts it created stay, and can no longer sign in with it")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.IDP_MANAGE)
    public Uni<Boolean> deleteIdentityProvider(@Name("id") Long id) {
        return service.delete(id);
    }

    @Mutation("addProviderGroupMapping")
    @Description("Says which local group a claim value puts somebody in")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.IDP_MANAGE)
    public Uni<Boolean> addProviderGroupMapping(
            @Name("providerId") Long providerId,
            @Name("mapping") @Valid GroupMappingRequest mapping) {
        return service.addMapping(providerId, mapping).replaceWith(true);
    }

    @Mutation("removeProviderGroupMapping")
    @Description("Removes a mapping; it stops applying at the next sign-in through that provider")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.IDP_MANAGE)
    public Uni<Boolean> removeProviderGroupMapping(@Name("mappingId") Long mappingId) {
        return service.removeMapping(mappingId);
    }
}
