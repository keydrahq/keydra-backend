package io.keydra.schedule.service;

import io.keydra.cluster.dto.LeadershipChanged;
import io.keydra.cluster.service.InstanceRegistry;
import io.keydra.cluster.service.Leadership;
import io.keydra.common.vertx.OwnContext;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.schedule.exception.ScheduleRefusedException;
import io.keydra.schedule.persistence.ScheduleRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Keeps the scheduler's idea of what runs when in step with the rows.
 *
 * <p>Quarkus' programmatic scheduler rather than {@code @Scheduled}, because the cadences are rows
 * somebody edits while the application is running and an annotation is fixed when the image is
 * built. Every schedule is registered under its own id, so changing one re-registers one rather
 * than rebuilding the set.
 *
 * <p>Registered at startup and after every write. The alternative — a single job that wakes each
 * minute and works out what is due — means re-implementing cron and getting the edge cases wrong;
 * this way the expression is parsed by the thing that already parses them, and a bad one is refused
 * when it is typed.
 *
 * <p>Every instance registers every schedule, and only the one holding the chores actually runs it.
 * Splitting it that way rather than registering only on the leader keeps a schedule's next run
 * something any instance can answer — the list would otherwise say "—" on two machines out of three
 * — and leaves the decision at the moment it has to be true rather than at handover.
 *
 * <p>Rows are re-read on a slow tick because another instance can write one. Only what actually
 * changed is re-registered: unscheduling and rescheduling a job every half minute is a good way to
 * lose the run that was about to happen.
 */
@ApplicationScoped
public class JobScheduler {

    private static final Logger LOG = Logger.getLogger(JobScheduler.class);

    private final Scheduler scheduler;
    private final ScheduleRepository repository;
    private final JobRunner runner;
    private final Leadership leadership;
    private final InstanceRegistry instances;
    private final Vertx vertx;
    private final int reconcileSeconds;

    /** What is on the clock, and in what shape, so a re-read can tell what actually moved. */
    private final Map<Long, String> registered = new ConcurrentHashMap<>();

    /** The re-read timer, kept so it can be stopped. See {@link #onStop}. */
    private volatile Long reconcileTimer;

    @Inject
    JobScheduler(
            Scheduler scheduler,
            ScheduleRepository repository,
            JobRunner runner,
            Leadership leadership,
            InstanceRegistry instances,
            Vertx vertx,
            @ConfigProperty(name = "keydra.cluster.reconcile-seconds") int reconcileSeconds) {
        this.scheduler = scheduler;
        this.repository = repository;
        this.runner = runner;
        this.leadership = leadership;
        this.instances = instances;
        this.vertx = vertx;
        this.reconcileSeconds = Math.max(5, reconcileSeconds);
    }

    void onStart(@Observes StartupEvent ignored) {
        try {
            VertxContextSupport.subscribeAndAwait(this::registerAll);
        } catch (Throwable failure) {
            // A schedule that cannot be registered is one that will not run, which is worth an
            // error in the log. It is not worth refusing to start: the rest of Keydra works,
            // and an instance that will not come up is harder to fix than one that says why.
            LOG.error("Could not register the scheduled jobs", failure);
        }
        reconcileTimer =
                vertx.setPeriodic(
                        reconcileSeconds * 1000L,
                        timer ->
                                OwnContext.run(
                                        vertx,
                                        this::registerAll,
                                        failure ->
                                                LOG.debugf(
                                                        failure,
                                                        "Could not re-read the scheduled jobs")));
    }

    /**
     * Stops the re-reading.
     *
     * <p>Not housekeeping. Vert.x outlives the application it was started for — a development
     * restart replaces the application and keeps the event loops — so a timer nobody cancels goes
     * on firing into the next one, where it reaches for a session factory that is still being
     * built. What that looks like is an application that never finishes starting.
     */
    void onStop(@Observes ShutdownEvent ignored) {
        if (reconcileTimer != null) {
            vertx.cancelTimer(reconcileTimer);
            reconcileTimer = null;
        }
    }

    /**
     * Reads the rows and makes the clock match them.
     *
     * <p>Also the reconciliation: a schedule written, edited or deleted by another instance arrives
     * here, and one that has not changed is left alone rather than taken off the clock and put back
     * on it.
     */
    @WithSession
    Uni<Integer> registerAll() {
        return repository
                .enabled()
                .map(
                        jobs -> {
                            jobs.forEach(this::register);
                            forgetThoseMissingFrom(jobs);
                            LOG.debugf("%d scheduled jobs on the clock", registered.size());
                            return jobs.size();
                        });
    }

    /** Takes off the clock anything the rows no longer have — including another instance's edit. */
    private void forgetThoseMissingFrom(List<ScheduledJob> jobs) {
        var live = new HashMap<>(registered);
        jobs.forEach(job -> live.remove(job.id));
        live.keySet().forEach(this::unregister);
    }

    /** Puts one schedule on the clock, replacing whatever was there under the same id. */
    public void register(ScheduledJob job) {
        if (!job.enabled) {
            unregister(job.id);
            return;
        }
        if (Objects.equals(registered.get(job.id), shapeOf(job))) {
            // Nothing about when it runs has changed. Re-registering it would move the next
            // fire time, and doing that on a timer is how a nightly job never runs at all.
            return;
        }
        unregister(job.id);
        try {
            scheduler
                    .newJob(identity(job.id))
                    .setCron(job.cron)
                    // An asynchronous task, which is not a detail: Quarkus runs one on a
                    // Vert.x duplicated context, and everything below here is Hibernate
                    // Reactive, which runs on nothing else. The obvious-looking alternative
                    // — an ordinary task that subscribes and waits — is what was here, and
                    // it failed on every single run with "the current thread cannot be
                    // blocked", because the thread the scheduler offers is not one that may
                    // be blocked. Nothing surfaced it: the failure was in the runner rather
                    // than in the schedule, so the page showed a job that had simply never
                    // run.
                    .setAsyncTask(execution -> runFor(job.id))
                    .schedule();
            registered.put(job.id, shapeOf(job));
        } catch (RuntimeException badExpression) {
            throw new ScheduleRefusedException(
                    "Keydra cannot read '" + job.cron + "' as a schedule");
        }
    }

    /**
     * What the scheduler runs when a schedule comes round.
     *
     * <p>Public so a test can run it the way the scheduler does — from a thread that may not be
     * blocked. That is not a hypothetical: this method exists because the version before it
     * blocked, failed on every scheduled run, and left a page showing a job that had never run.
     *
     * <p>A failure is logged and swallowed. {@link JobRunner} records its own outcomes, so anything
     * arriving here is the recording itself failing, which has nowhere left to go.
     *
     * <p>Nothing happens unless this instance holds the chores. Every instance has the schedule, so
     * without this check a nightly flush behind a load balancer would be run once per instance —
     * and the second copy looks exactly like the first in the history.
     */
    public Uni<Void> runFor(Long jobId) {
        if (!leadership.isLeader()) {
            LOG.debugf("Not running scheduled job %d: another instance holds the chores", jobId);
            return Uni.createFrom().voidItem();
        }
        return runner.run(jobId, false)
                .onFailure()
                .invoke(failure -> LOG.errorf(failure, "Could not run scheduled job %d", jobId))
                .onFailure()
                .recoverWithNull()
                .replaceWithVoid();
    }

    /**
     * Ends the runs left behind by an instance that is not here any more.
     *
     * <p>When this instance takes on the chores, and that is exactly the right moment. The
     * scheduler only ever runs on whichever instance holds them, so a run row still saying RUNNING
     * belongs to the instance that held them before — which, if the chores have just moved, is an
     * instance that stopped. Nobody else was ever going to write an ending on it.
     *
     * <p>The runs are not started again. A schedule is a statement about when its job should
     * happen, and the honest answer for a nightly flush interrupted at three is to run it at three
     * tomorrow rather than at whatever minute a handover happened to be noticed. What this fixes is
     * a history that lies, which is the part that stops somebody deciding anything.
     */
    void onLeadership(@Observes LeadershipChanged change) {
        if (!change.leader()) {
            return;
        }
        OwnContext.run(
                vertx,
                () ->
                        instances
                                .live()
                                .flatMap(
                                        live ->
                                                repository.interruptRunsOf(
                                                        live.stream()
                                                                .map(instance -> instance.id)
                                                                .collect(Collectors.toSet())))
                                .invoke(
                                        ended -> {
                                            if (ended > 0) {
                                                LOG.infof(
                                                        "%d scheduled runs were left unfinished by"
                                                                + " an instance that is gone",
                                                        ended);
                                            }
                                        })
                                .replaceWithVoid(),
                failure ->
                        LOG.warnf(failure, "Could not end the runs of instances that have gone"));
    }

    public void unregister(Long jobId) {
        registered.remove(jobId);
        scheduler.unscheduleJob(identity(jobId));
    }

    /**
     * What about a schedule decides when it runs. Two jobs of the same shape need no re-reading.
     */
    private static String shapeOf(ScheduledJob job) {
        return job.cron + "|" + job.enabled;
    }

    /**
     * Whether an expression is one the scheduler can read.
     *
     * <p>Asked before a schedule is saved, by registering it and taking it away again. Parsing it a
     * second way here would mean two things deciding what a cron expression means, and the one that
     * matters is the one that will run it.
     */
    public void checkCron(String cron) {
        String probe = "keydra-cron-probe";
        try {
            scheduler.newJob(probe).setCron(cron).setTask(execution -> {}).schedule();
        } catch (RuntimeException unreadable) {
            throw new ScheduleRefusedException("Keydra cannot read '" + cron + "' as a schedule");
        } finally {
            // Taking the probe away must not become the failure that is reported: whatever
            // goes wrong here, the answer somebody needs is about their expression.
            try {
                scheduler.unscheduleJob(probe);
            } catch (RuntimeException ignored) {
                LOG.debugf("Could not remove the cron probe for '%s'", cron);
            }
        }
    }

    /** One identity per schedule, so re-registering replaces rather than duplicates. */
    private static String identity(Long jobId) {
        return "keydra-schedule-" + jobId;
    }

    /**
     * When the scheduler expects to run this next.
     *
     * <p>Asked of the scheduler rather than worked out from the expression, so what a schedule list
     * shows is what will actually happen rather than a second opinion about it.
     */
    public java.time.Instant nextRun(Long jobId) {
        var scheduled = scheduler.getScheduledJob(identity(jobId));
        return scheduled == null || scheduled.getNextFireTime() == null
                ? null
                : scheduled.getNextFireTime();
    }
}
