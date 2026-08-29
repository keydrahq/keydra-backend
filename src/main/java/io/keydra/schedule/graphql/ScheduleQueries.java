package io.keydra.schedule.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.schedule.dto.ScheduleDtos.JobRunSummary;
import io.keydra.schedule.dto.ScheduleDtos.JobTypeInfo;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleRequest;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleSummary;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.service.ScheduleService;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Arranged work, and what became of it.
 *
 * <p>Transport only. It calls the same service the resource calls, which already filters to the
 * targets the caller can see — a schedule is about a target, and a target somebody cannot reach is
 * one they have no business reading the schedules of.
 *
 * <p>Guarded by both things that guard the resource. The role is the coarse gate; the permission is
 * the real answer, read from the grant tables against the target named in the request, which is
 * what makes a role somebody defined themselves work here at all.
 */
@GraphQLApi
@OneAtATime
public class ScheduleQueries {

    private final ScheduleService service;

    @Inject
    ScheduleQueries(ScheduleService service) {
        this.service = service;
    }

    @Query("schedules")
    @Description("Every schedule, with when it last ran and when it runs next")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<ScheduleSummary>> schedules() {
        return service.list();
    }

    /**
     * The kinds of work that can be scheduled.
     *
     * <p>A plain list rather than a connection, and deliberately: this is an enum, so it is as long
     * as the code says and never longer. Paging a fixed set is machinery nobody needs.
     */
    @Query("scheduleJobTypes")
    @Description("The kinds of work that can be scheduled, with the permission each one needs")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<JobTypeInfo>> scheduleJobTypes() {
        return Uni.createFrom()
                .item(
                        Arrays.stream(JobType.values())
                                .map(type -> new JobTypeInfo(type.name(), type.required().name()))
                                .toList());
    }

    @Query("scheduleRuns")
    @Description("What became of the arranged work, newest first")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<JobRunSummary>> scheduleRuns(
            @Name("jobId") @Description("One schedule's runs, or every schedule's") Long jobId) {
        return service.history(jobId);
    }

    // --- Changing them ------------------------------------------------------

    @Mutation("createSchedule")
    @Description("Arranges a job to run on a cron expression")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<ScheduleSummary> createSchedule(
            @Name("connectionId") @Description("The target the job runs against") Long connectionId,
            @Name("schedule") @Valid ScheduleRequest schedule) {
        return service.create(schedule);
    }

    @Mutation("updateSchedule")
    @Description("Changes a schedule")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<ScheduleSummary> updateSchedule(
            @Name("id") Long id,
            @Name("connectionId") @Description("The target the job runs against") Long connectionId,
            @Name("schedule") @Valid ScheduleRequest schedule) {
        return service.update(id, schedule);
    }

    /**
     * Removes a schedule.
     *
     * <p>Answers whether there was one to remove rather than failing when there was not. Deleting
     * something twice is not an error worth a page turning red: the second time, the thing is
     * already gone, which is what was asked for.
     */
    @Mutation("deleteSchedule")
    @Description("Removes a schedule")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<Boolean> deleteSchedule(
            @Name("id") Long id,
            @Name("connectionId") @Description("The target the job runs against")
                    Long connectionId) {
        return service.delete(id);
    }

    @Mutation("runSchedule")
    @Description("Runs a scheduled job now, without waiting for its cron")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SCHEDULE_MANAGE, connection = "connectionId")
    public Uni<JobRunSummary> runSchedule(
            @Name("id") Long id,
            @Name("connectionId") @Description("The target the job runs against")
                    Long connectionId) {
        return service.runNow(id);
    }
}
