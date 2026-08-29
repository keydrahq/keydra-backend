package io.keydra.keys.rest;

import io.keydra.engine.Database;
import io.keydra.keys.service.DatabaseService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/** The keyspaces a target holds, so one of them can be chosen to browse. */
@Path("/api/v1/connections/{connectionId}/databases")
@Produces(MediaType.APPLICATION_JSON)
public class Databases {

    private final DatabaseService databases;

    @Inject
    Databases(DatabaseService databases) {
        this.databases = databases;
    }

    @GET
    @Operation(
            summary = "The databases this target holds",
            description =
                    "Every database the server is configured for, with how many keys each holds — a"
                            + " list that hid the empty ones could not be used to move into one. A"
                            + " clustered or sentinel target answers with the one keyspace it has.")
    @APIResponse(responseCode = "200", description = "The databases")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    public Uni<List<Database>> list(@PathParam("connectionId") Long connectionId) {
        return databases.list(connectionId);
    }
}
