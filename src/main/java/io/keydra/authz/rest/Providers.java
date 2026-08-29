package io.keydra.authz.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.ProviderDtos.GroupMappingRequest;
import io.keydra.authz.dto.ProviderDtos.ProviderRequest;
import io.keydra.authz.dto.ProviderDtos.ProviderSummary;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.ProviderAdminService;
import io.keydra.authz.service.PublicUrl;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The places people can sign in from, as rows.
 *
 * <p>Adding a way into Keydra should not be a redeploy, which is the whole of why these are here
 * rather than in a configuration file. A client secret may be sent to this resource and is never
 * sent back: like a target's password, the only thing the API will say about one is whether it
 * exists.
 */
@Path("/api/v1/authz/providers")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Identity providers", description = "Where people sign in from")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.IDP_MANAGE)
public class Providers {

    private final ProviderAdminService service;
    private final PublicUrl publicUrl;

    @Inject
    Providers(ProviderAdminService service, PublicUrl publicUrl) {
        this.service = service;
        this.publicUrl = publicUrl;
    }

    @GET
    @Operation(
            summary = "Every configured provider",
            description =
                    "Including the redirect URI each one has to be told about, which is the thing"
                            + " that must match exactly and the usual reason a first attempt is"
                            + " refused.")
    @APIResponse(responseCode = "200", description = "The providers")
    public Uni<List<ProviderSummary>> list(@Context UriInfo uriInfo) {
        return service.providers(publicUrl.of(uriInfo));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Add a provider",
            description =
                    "An OIDC provider that names an issuer has its endpoints discovered now rather"
                            + " than at each sign-in, so a wrong issuer is answered here, where"
                            + " somebody is waiting and able to fix it.")
    @APIResponse(responseCode = "201", description = "Added")
    @APIResponse(responseCode = "409", description = "That key is taken, or discovery failed")
    @Audited("provider.create")
    public Uni<RestResponse<ProviderSummary>> create(
            @Valid ProviderRequest request, @Context UriInfo uriInfo) {
        return service.create(request, publicUrl.of(uriInfo))
                .map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change a provider",
            description =
                    "An absent client secret leaves the stored one alone. Saving re-runs discovery,"
                            + " which is also how a provider whose endpoints have moved is brought"
                            + " up to date.")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "No such provider, or discovery failed")
    @Audited("provider.update")
    public Uni<ProviderSummary> update(
            @PathParam("id") Long id, @Valid ProviderRequest request, @Context UriInfo uriInfo) {
        return service.update(id, request, publicUrl.of(uriInfo));
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a provider",
            description =
                    "The accounts it created stay, and stop being able to sign in. Deleting the"
                            + " people along with the way they arrived would take their grants with"
                            + " them, and a provider is often removed to be replaced.")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("provider.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/{id}/group-mappings")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Map a claim value to a Keydra group",
            description =
                    "Membership of a mapped group is replaced at every sign-in by what the provider"
                            + " says. Groups nobody mapped are left alone.")
    @APIResponse(responseCode = "204", description = "Mapped")
    @Audited("provider.mapping.add")
    public Uni<RestResponse<Void>> addMapping(
            @PathParam("id") Long id, @Valid GroupMappingRequest request) {
        return service.addMapping(id, request).map(ignored -> RestResponse.noContent());
    }

    @DELETE
    @Path("/group-mappings/{mappingId}")
    @Operation(
            summary = "Remove a mapping",
            description =
                    "The group stops being the provider's to fill, and whoever is in it stays in"
                            + " it until somebody takes them out.")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("provider.mapping.remove")
    public Uni<RestResponse<Void>> removeMapping(@PathParam("mappingId") Long mappingId) {
        return service.removeMapping(mappingId).map(ignored -> RestResponse.noContent());
    }
}
