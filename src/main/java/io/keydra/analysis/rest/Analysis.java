package io.keydra.analysis.rest;

import io.keydra.analysis.dto.KeyspaceReport;
import io.keydra.analysis.service.KeyspaceAnalyser;
import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/** Where a target's memory went, and how much of it will ever be released. */
@Path("/api/v1/connections/{connectionId}/analysis")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Analysis {

    private final KeyspaceAnalyser analyser;

    @Inject
    Analysis(KeyspaceAnalyser analyser) {
        this.analyser = analyser;
    }

    @GET
    @Path("/keyspace")
    @Operation(
            summary = "Where this target's memory went",
            description =
                    "Groups a sample of the keyspace by namespace, by type and by how long each key"
                        + " has left, so the two questions a full server actually raises — which"
                        + " namespace grew, and how much of this is never coming back — can be"
                        + " answered without reading a list of the biggest keys and guessing."
                        + " Measured over a sample because MEMORY USAGE costs a round trip per key;"
                        + " the report says how many it looked at and how many there are.")
    @APIResponse(responseCode = "200", description = "The report")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.ANALYSIS_READ, connection = "connectionId")
    public Uni<KeyspaceReport> keyspace(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("db") Integer database,
            @QueryParam("sample") Integer sampleSize) {
        return analyser.analyse(connectionId, database, sampleSize);
    }
}
