package io.keydra.schedule.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.schedule.dto.ScheduleDtos.JobRunSummary;
import io.keydra.schedule.dto.ScheduleDtos.JobTypeInfo;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleRequest;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleSummary;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.service.ScheduleService;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Work arranged to happen on its own.
 *
 * <p>Everything here is about one target, so the permission is asked about that target — and it is
 * asked twice: once when a schedule is written and again every time it runs. A schedule is a way of
 * doing something later, not a way of keeping access somebody has had taken away.
 */
@Path("/api/v1/schedules")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Schedules", description = "Work arranged to happen on its own")
@RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
public class Schedules {

    private final ScheduleService service;

    @Inject
    Schedules(ScheduleService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Every schedule, with when it last ran and when it runs next",
            description =
                    "Filtered to what the caller can see, the same way the catalog is: a schedule"
                            + " is about a target, and a target somebody cannot reach is one they"
                            + " have no business reading the schedules of.")
    @APIResponse(responseCode = "200", description = "The schedules")
    public Uni<List<ScheduleSummary>> list() {
        return service.list();
    }

    @GET
    @Path("/job-types")
    @Operation(
            summary = "The kinds of work that can be scheduled",
            description =
                    "With the permission each one needs, so the interface can say why an option is"
                            + " not offered rather than leaving it out silently.")
    @APIResponse(responseCode = "200", description = "The job types")
    public Uni<List<JobTypeInfo>> jobTypes() {
        return Uni.createFrom()
                .item(
                        Arrays.stream(JobType.values())
                                .map(type -> new JobTypeInfo(type.name(), type.required().name()))
                                .toList());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Arrange work to happen on its own",
            description =
                    "The cron expression is checked by the scheduler that will run it and the"
                            + " settings by the handler that will read them, so a schedule that"
                            + " cannot work is refused while somebody is looking at it.")
    @APIResponse(responseCode = "201", description = "Arranged")
    @APIResponse(responseCode = "409", description = "The schedule cannot work as written")
    @Audited("schedule.create")
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<RestResponse<ScheduleSummary>> create(
            @QueryParam("connectionId") Long connectionId, @Valid ScheduleRequest request) {
        return service.create(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Change a schedule")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "The schedule cannot work as written")
    @Audited("schedule.update")
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<ScheduleSummary> update(
            @PathParam("id") Long id,
            @QueryParam("connectionId") Long connectionId,
            @Valid ScheduleRequest request) {
        return service.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Remove a schedule, and the record of what it did")
    @APIResponse(responseCode = "204", description = "Removed")
    @Audited("schedule.delete")
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<RestResponse<Void>> delete(
            @PathParam("id") Long id, @QueryParam("connectionId") Long connectionId) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }

    @POST
    @Path("/{id}/run")
    @Operation(
            summary = "Run it now",
            description =
                    "The first thing anybody does after writing a schedule. Recorded as a manual"
                            + " run, so a history can tell one nobody expected from one the clock"
                            + " asked for.")
    @APIResponse(responseCode = "200", description = "What the attempt did")
    @Audited("schedule.run")
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<JobRunSummary> runNow(
            @PathParam("id") Long id, @QueryParam("connectionId") Long connectionId) {
        return service.runNow(id);
    }

    @GET
    @Path("/runs")
    @Operation(
            summary = "What the schedules have done",
            description = "Newest first, for one schedule or for all of them.")
    @APIResponse(responseCode = "200", description = "The attempts")
    public Uni<List<JobRunSummary>> runs(@QueryParam("jobId") Long jobId) {
        return service.history(jobId);
    }
}
