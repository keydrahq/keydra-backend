package io.keydra.schedule.dto;

import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.RunOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire shapes for work arranged to happen on its own. */
public final class ScheduleDtos {

    private ScheduleDtos() {}

    /**
     * A schedule as somebody reading a list sees it.
     *
     * @param connectionName carried beside the id because a list of schedules is read rather than
     *     joined, and "Flush the session cache" means nothing without knowing which server
     * @param nextRunAt when it is next due, which is the question a schedule list is opened with
     */
    @Schema(name = "ScheduleSummary", description = "Work arranged to happen on its own")
    public record ScheduleSummary(
            Long id,
            String name,
            Long connectionId,
            String connectionName,
            JobType jobType,
            String cron,
            boolean enabled,
            String settings,
            String createdBy,
            Instant createdAt,
            Instant lastRunAt,
            RunOutcome lastOutcome,
            Instant nextRunAt) {}

    /**
     * A schedule to create or change.
     *
     * @param settings what this kind of work needs, as JSON — read by its handler and nothing else
     */
    @Schema(name = "ScheduleRequest", description = "A schedule to create or change")
    public record ScheduleRequest(
            @NotBlank String name,
            @NotNull Long connectionId,
            @NotNull JobType jobType,
            @NotBlank String cron,
            Boolean enabled,
            String settings,
            @Schema(
                            description =
                                    "The target's own name, required only when the job would empty"
                                        + " a guarded target. Asked here rather than at three in"
                                        + " the morning: a confirmation is about somebody's intent"
                                        + " at the moment of asking, and a schedule is asked for"
                                        + " once.")
                    String confirmTarget,
            @Schema(
                            description =
                                    "The other end's own name, required only when the job would"
                                        + " write into a guarded target that is not the one above."
                                        + " A copy is two servers, and a schedule that would"
                                        + " overwrite the far one is the same act as one that"
                                        + " empties the near one.")
                    String confirmSecond) {}

    /** One attempt, and what it did. */
    @Schema(name = "JobRunSummary", description = "One attempt at a scheduled job")
    public record JobRunSummary(
            Long id,
            Long jobId,
            String jobName,
            Instant startedAt,
            Instant finishedAt,
            RunOutcome outcome,
            String detail,
            boolean wasManual) {}

    /**
     * One kind of work, described by the server.
     *
     * @param requires the permission somebody needs on the target for this kind of work, named the
     *     way {@code /auth/permissions} names them so an interface can compare the two without a
     *     translation table in the middle
     */
    @Schema(name = "JobTypeInfo", description = "A kind of work that can be scheduled")
    public record JobTypeInfo(String name, String requires) {}
}
