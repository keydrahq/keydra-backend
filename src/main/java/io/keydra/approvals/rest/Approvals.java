package io.keydra.approvals.rest;

import io.keydra.approvals.dto.ApprovalDtos.ApprovalSummary;
import io.keydra.approvals.dto.ApprovalDtos.DeclineRequest;
import io.keydra.approvals.service.ApprovalService;
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
 * Operations waiting for somebody who is not the person who asked.
 *
 * <p>Nothing here names a permission in an annotation, and that is the phase's own finding applied
 * to itself: which permission answering a request needs depends on what the request says — {@code
 * keys:delete} for a purge, {@code migration:run} on both ends for a migration — so a fixed
 * annotation would either be wrong for most rows or checked against something the caller supplied.
 * The decision is made in the service, against the row.
 */
@Path("/api/v1/approvals")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Approvals", description = "Operations waiting for a second person")
@RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
public class Approvals {

    private final ApprovalService service;

    @Inject
    Approvals(ApprovalService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Operations waiting for a second person",
            description =
                    "Filtered to what the caller can see, the same way the schedules are: a request"
                            + " is about a target, and a target somebody cannot reach is one whose"
                            + " pending operations are none of their business. A migration is about"
                            + " two, and both have to be visible.")
    @APIResponse(responseCode = "200", description = "The requests")
    public Uni<List<ApprovalSummary>> list(@QueryParam("all") boolean all) {
        return service.list(!all);
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "One request, with what it would do")
    @APIResponse(responseCode = "200", description = "The request")
    public Uni<ApprovalSummary> one(@PathParam("id") Long id) {
        return service.one(id);
    }

    @POST
    @Path("/{id}/approve")
    @Operation(
            summary = "Agree to an operation, which starts it",
            description =
                    "Answered as soon as the work is under way rather than when it finishes: a"
                        + " purge of a large keyspace takes as long as it takes, and how it ended"
                        + " arrives on the notification hub. Never your own request, whatever you"
                        + " hold.")
    @APIResponse(responseCode = "200", description = "The request, now running")
    @APIResponse(responseCode = "409", description = "Already answered, or not yours to answer")
    @Audited("approval.approve")
    public Uni<ApprovalSummary> approve(@PathParam("id") Long id) {
        return service.approve(id);
    }

    @POST
    @Path("/{id}/decline")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Say no, and say why",
            description =
                    "The reason is for the person who asked to read, which is why declining takes"
                            + " one at all.")
    @APIResponse(responseCode = "200", description = "The request, now declined")
    @APIResponse(responseCode = "409", description = "Already answered, or not yours to answer")
    @Audited("approval.decline")
    public Uni<ApprovalSummary> decline(@PathParam("id") Long id, @Valid DeclineRequest request) {
        return service.decline(id, request == null ? null : request.reason());
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Withdraw a request you made",
            description = "Only the person who asked, and only while nobody has answered it.")
    @APIResponse(responseCode = "200", description = "The request, now withdrawn")
    @APIResponse(responseCode = "409", description = "Already answered, or not yours to withdraw")
    @Audited("approval.withdraw")
    public Uni<ApprovalSummary> withdraw(@PathParam("id") Long id) {
        return service.withdraw(id);
    }
}
