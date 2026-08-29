package io.keydra.values.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.keydra.values.dto.MutationResult;
import io.keydra.values.dto.ValueMutation;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.keydra.values.service.ValueService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The value behind one key.
 *
 * <p>The key travels as a query parameter, not a path segment, for the same reason it does in the
 * key endpoints: a key may contain any byte, and encoding one into a path is a reliable source of
 * bugs.
 */
@Path("/api/v1/connections/{connectionId}/value")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Values", description = "Read and edit the value behind a key")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Values {

    private final ValueService service;

    @Inject
    Values(ValueService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Read a page of a key's value",
            description =
                    "Pages by cursor for hash, set and sorted-set, by index for list and by entry"
                        + " id for stream. The value is decoded server-side; pass an encoding to"
                        + " force one instead of letting it be detected.")
    @APIResponse(responseCode = "200", description = "A page of the value")
    @APIResponse(responseCode = "404", description = "No such connection, or no such key")
    @RequiresPermission(value = Permission.VALUES_READ, connection = "connectionId")
    public Uni<ValuePage> read(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @QueryParam("key") String key,
            @QueryParam("cursor") String cursor,
            @QueryParam("count") Integer count,
            @QueryParam("encoding") String encoding) {
        ValueQuery query =
                new ValueQuery(
                        key,
                        cursor == null ? ValueQuery.CURSOR_START : cursor,
                        count == null ? ValueQuery.DEFAULT_COUNT : count);
        return service.read(connectionId, database, query, encoding);
    }

    @GET
    @Path("/encodings")
    @Operation(summary = "List the decoders a client may request")
    @APIResponse(responseCode = "200", description = "Decoder ids")
    public List<String> encodings() {
        return service.encodings();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change a value",
            description = "The body names which operation to apply; see ValueMutation.")
    @APIResponse(responseCode = "200", description = "How many elements changed")
    @APIResponse(responseCode = "404", description = "No such connection, or no such key")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("value.mutate")
    @RequiresPermission(value = Permission.VALUES_WRITE, connection = "connectionId")
    public Uni<MutationResult> mutate(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @Valid ValueMutation mutation) {
        return service.mutate(connectionId, database, mutation).map(MutationResult::new);
    }
}
