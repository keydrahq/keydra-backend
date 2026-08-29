package io.keydra.preferences.rest;

import io.keydra.preferences.dto.PreferenceDtos.PreferenceRequest;
import io.keydra.preferences.service.PreferenceService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What one person prefers, kept with their account.
 *
 * <p>{@code @PermitAll} rather than {@code @Authenticated}, and that is the whole design in one
 * annotation: an instance with enforcement off has nobody to keep preferences for, and it is still
 * an instance somebody uses. Asking anonymously answers "nothing, and nowhere to put it", the
 * browser keeps its own copy, and the interface behaves exactly as it did before any of this
 * existed. Refusing instead would make an open instance an instance with no theme switch.
 *
 * <p>Nothing here takes an account: the caller's own is the only one that can be read or written,
 * which is why there is no id anywhere in these paths.
 */
@Path("/api/v1/preferences")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Preferences", description = "What you prefer, kept with your account")
@PermitAll
public class Preferences {

    private final PreferenceService service;

    @Inject
    Preferences(PreferenceService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Everything you prefer",
            description =
                    "Empty, and marked as not stored, on an instance with no accounts — the"
                            + " browser's own copy is then the only copy.")
    @APIResponse(responseCode = "200", description = "Your preferences")
    public Uni<io.keydra.preferences.dto.PreferenceDtos.Preferences> mine() {
        return service.mine();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Set one preference",
            description = "Answers false when there is no account to keep it with.")
    @APIResponse(responseCode = "200", description = "Whether it was kept")
    public Uni<Boolean> set(@Valid PreferenceRequest request) {
        return service.set(request.name(), request.value());
    }

    @DELETE
    @Path("/{name}")
    @Operation(
            summary = "Forget one preference",
            description = "Whatever the interface defaults to applies again.")
    @APIResponse(responseCode = "200", description = "Whether there was one to forget")
    public Uni<Boolean> forget(@PathParam("name") String name) {
        return service.forget(name);
    }
}
