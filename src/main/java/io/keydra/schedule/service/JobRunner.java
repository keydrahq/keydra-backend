package io.keydra.schedule.service;

import io.keydra.authz.entity.Permission;
import io.keydra.cluster.service.Leadership;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.schedule.entity.JobRun;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.RunOutcome;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.schedule.job.JobHandler;
import io.keydra.schedule.persistence.ScheduleRepository;
import io.keydra.telemetry.service.KeydraMeters;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Runs one scheduled job and records what happened.
 *
 * <p>Three steps, each with its own database boundary and nothing holding one open in between: read
 * the schedule, commit the attempt, do the work, commit the outcome. The shape is deliberate. The
 * attempt is written and committed <em>before</em> the work starts, so a job that hangs is visible
 * as one that hung — inside a single transaction the row would appear only once the work was over,
 * making a hung job look exactly like one that never ran. And the work itself happens outside any
 * session, because a flush of a large keyspace takes as long as it takes and a session held across
 * it is a database connection held across it.
 */
@ApplicationScoped
public class JobRunner {

    private static final Logger LOG = Logger.getLogger(JobRunner.class);

    /** How much of a failure is worth keeping. Enough to recognise it, not a stack trace. */
    private static final int DETAIL_LIMIT = 480;

    private final ScheduleRepository repository;
    private final RunGuard guard;
    private final Leadership leadership;
    private final NotificationHub hub;
    private final KeydraMeters meters;
    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    @Inject
    JobRunner(
            ScheduleRepository repository,
            RunGuard guard,
            Leadership leadership,
            NotificationHub hub,
            KeydraMeters meters,
            Instance<JobHandler> discovered) {
        this.repository = repository;
        this.guard = guard;
        this.leadership = leadership;
        this.hub = hub;
        this.meters = meters;
        discovered.forEach(handler -> handlers.put(handler.handles(), handler));
    }

    /** The handler for a job type, or nothing — which is a job type somebody forgot to write. */
    public JobHandler handlerFor(JobType type) {
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("No handler for " + type);
        }
        return handler;
    }

    /**
     * Runs a job, whether the clock asked or somebody did.
     *
     * @param manual true when a person pressed a button, which the history records so a run nobody
     *     expected can be told from one the schedule made
     */
    public Uni<JobRun> run(Long jobId, boolean manual) {
        return repository
                .forRun(jobId)
                .flatMap(
                        job -> {
                            if (job == null) {
                                return Uni.createFrom()
                                        .failure(new IllegalStateException("No such schedule"));
                            }
                            return repository
                                    .startRun(jobId, manual, leadership.instanceId())
                                    .flatMap(run -> attempt(job, run));
                        });
    }

    /**
     * Where a permission is held, which is not decoration.
     *
     * <p>"copy:run on this target" and "script:run on this instance" are two different things to go
     * and ask an administrator for, and a run history that said "on this target" for both would
     * send somebody to the wrong page.
     */
    private static String where(Permission permission) {
        return permission.level() == Permission.Level.INSTANCE
                ? " on this instance"
                : " on this target";
    }

    private Uni<JobRun> attempt(ScheduledJob job, JobRun run) {
        JobHandler handler = handlerFor(job.jobType);
        return guard.missing(job, handler.alsoNeeds(job))
                .flatMap(
                        missing -> {
                            if (missing != null) {
                                return finish(
                                        job,
                                        run,
                                        RunOutcome.REFUSED,
                                        job.createdBy
                                                + " no longer holds "
                                                + missing.id()
                                                + where(missing));
                            }
                            return handler.run(job)
                                    .flatMap(detail -> finish(job, run, RunOutcome.DONE, detail))
                                    .onFailure()
                                    .recoverWithUni(
                                            failure -> {
                                                LOG.warnf(
                                                        failure,
                                                        "Scheduled job '%s' failed",
                                                        job.name);
                                                return finish(
                                                        job,
                                                        run,
                                                        RunOutcome.FAILED,
                                                        message(failure));
                                            });
                        });
    }

    private Uni<JobRun> finish(ScheduledJob job, JobRun run, RunOutcome outcome, String detail) {
        String kept =
                detail == null
                        ? null
                        : detail.substring(0, Math.min(detail.length(), DETAIL_LIMIT));

        meters.scheduleRan(outcome.name(), run.wasManual);
        return repository
                .finishRun(run.id, outcome, kept)
                .invoke(
                        finished -> {
                            // Every run says it happened, so a page showing "last run" learns
                            // about it instead of asking again every fifteen seconds.
                            hub.broadcast(
                                    NotificationCategory.SCHEDULE_RAN,
                                    job.connectionId,
                                    Map.of("jobId", job.id, "outcome", outcome.name()));

                            // Failures also go out as their own thing, because that one is shown
                            // to a person. The first anybody hears of a broken schedule should
                            // not be the empty cache it was supposed to be filling.
                            if (outcome != RunOutcome.DONE) {
                                hub.broadcast(
                                        NotificationCategory.SCHEDULE_FAILED,
                                        job.connectionId,
                                        Map.of(
                                                "jobId",
                                                job.id,
                                                "name",
                                                job.name,
                                                "outcome",
                                                outcome.name(),
                                                "detail",
                                                kept == null ? "" : kept));
                            }
                        });
    }

    /** A failure in one line, which is what a run history has room for. */
    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
