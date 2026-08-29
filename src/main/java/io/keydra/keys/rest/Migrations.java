package io.keydra.keys.rest;

import io.keydra.keys.dto.MigrationJob;
import io.keydra.keys.service.KeyMigrationService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Every migration, whichever target started it.
 *
 * <p>A migration is between two targets, so a list kept under one of them is a list somebody has to
 * already know where to look for — and the page that answers "what is moving right now" cannot
 * begin by asking which target to ask.
 */
@Path("/api/v1/migrations")
@Produces(MediaType.APPLICATION_JSON)
public class Migrations {

    private final KeyMigrationService migrations;

    @Inject
    Migrations(KeyMigrationService migrations) {
        this.migrations = migrations;
    }

    @GET
    @Operation(
            summary = "Every migration this instance knows about",
            description =
                    "Running and finished alike, and kept across a restart. A job that was running"
                        + " when Keydra stopped is reported as interrupted rather than"
                        + " disappearing: the keys already written stay written, because a"
                        + " migration is a stream of independent writes rather than a transaction,"
                        + " and whoever decides whether to run it again needs to know which of the"
                        + " two happened. Filtered to the targets the caller can see, at both"
                        + " ends.")
    @APIResponse(responseCode = "200", description = "The jobs")
    public Uni<List<MigrationJob>> list() {
        return migrations.allJobs();
    }
}
