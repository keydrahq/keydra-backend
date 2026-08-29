package io.keydra.schedule.persistence;

import io.keydra.schedule.entity.JobRun;
import io.keydra.schedule.entity.RunOutcome;
import io.keydra.schedule.entity.ScheduledJob;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Reads and writes the schedules and what they have done. */
@ApplicationScoped
public class ScheduleRepository {

    public Uni<List<ScheduledJob>> all() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ScheduledJob order by name",
                                                ScheduledJob.class)
                                        .getResultList());
    }

    /** Only the ones that should be registered with the scheduler. */
    public Uni<List<ScheduledJob>> enabled() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ScheduledJob where enabled = true",
                                                ScheduledJob.class)
                                        .getResultList());
    }

    public Uni<ScheduledJob> byId(Long id) {
        return Panache.getSession().flatMap(session -> session.find(ScheduledJob.class, id));
    }

    public Uni<ScheduledJob> save(ScheduledJob job) {
        return Panache.getSession().flatMap(session -> session.persist(job).replaceWith(job));
    }

    /** Removes a schedule and its history; a run whose schedule is gone answers nothing. */
    public Uni<Boolean> delete(Long id) {
        return execute("delete from JobRun where jobId = :id", id)
                .flatMap(ignored -> execute("delete from ScheduledJob where id = :id", id))
                .map(deleted -> deleted > 0);
    }

    public Uni<JobRun> save(JobRun run) {
        return Panache.getSession().flatMap(session -> session.persist(run).replaceWith(run));
    }

    public Uni<JobRun> run(Long id) {
        return Panache.getSession().flatMap(session -> session.find(JobRun.class, id));
    }

    /** The newest attempts, for one schedule or for the whole instance. */
    public Uni<List<JobRun>> runs(Long jobId, int limit) {
        String query =
                jobId == null
                        ? "from JobRun order by startedAt desc"
                        : "from JobRun where jobId = :id order by startedAt desc";
        return Panache.getSession()
                .flatMap(
                        session -> {
                            var typed =
                                    session.createQuery(query, JobRun.class).setMaxResults(limit);
                            return jobId == null
                                    ? typed.getResultList()
                                    : typed.setParameter("id", jobId).getResultList();
                        });
    }

    /**
     * The schedule about to run, read in a session of its own.
     *
     * <p>Its own because the work that follows must not happen inside one: a flush of a large
     * keyspace takes as long as it takes, and a session held open across it is a connection held
     * open across it.
     */
    @WithSession
    public Uni<ScheduledJob> forRun(Long id) {
        return byId(id);
    }

    /**
     * Records an attempt before the work starts, and commits it.
     *
     * <p>Committed on its own rather than as part of the run, which is the whole point: a job that
     * never finishes has to be visible as one that never finished. Written inside the same
     * transaction as the work, the row would appear only once the work was over — leaving a hung
     * job indistinguishable from one that never ran, and those two call for opposite responses.
     */
    @WithTransaction
    public Uni<JobRun> startRun(Long jobId, boolean manual, String instanceId) {
        JobRun run = new JobRun();
        run.jobId = jobId;
        run.wasManual = manual;
        run.instanceId = instanceId;
        return save(run);
    }

    /**
     * Ends the runs of instances that are no longer here, and answers how many there were.
     *
     * <p>Called by whichever instance takes on the chores, because the scheduler runs on exactly
     * one instance and a run row it left behind has nobody else to finish it. Left alone, that row
     * says RUNNING for as long as the table exists — and a history in which a job has apparently
     * been running since Tuesday is a history nobody can use to decide anything.
     *
     * <p>Under a name that is not in the roster, and only under one. A run with no name is from
     * before this column existed and a run under a live instance's name is a run that is running;
     * sweeping either would be one machine declaring another machine's work dead.
     */
    @WithTransaction
    public Uni<Integer> interruptRunsOf(Collection<String> live) {
        Collection<String> names = live.isEmpty() ? List.of("") : live;
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update JobRun set outcome = :interrupted,"
                                                    + " finishedAt = :now, detail = :detail where"
                                                    + " outcome = :running and instanceId is not"
                                                    + " null and instanceId not in (:live)")
                                        .setParameter("interrupted", RunOutcome.INTERRUPTED)
                                        .setParameter("now", Instant.now())
                                        .setParameter(
                                                "detail",
                                                "The instance running this stopped before it"
                                                        + " finished")
                                        .setParameter("running", RunOutcome.RUNNING)
                                        .setParameter("live", names)
                                        .executeUpdate());
    }

    /** Writes how an attempt ended, and carries it onto the schedule's own "last run" columns. */
    @WithTransaction
    public Uni<JobRun> finishRun(Long runId, RunOutcome outcome, String detail) {
        return run(runId)
                .flatMap(
                        run -> {
                            run.outcome = outcome;
                            run.finishedAt = Instant.now();
                            run.detail = detail;
                            return byId(run.jobId)
                                    .map(
                                            job -> {
                                                if (job != null) {
                                                    job.lastRunAt = run.startedAt;
                                                    job.lastOutcome = outcome;
                                                }
                                                return run;
                                            });
                        });
    }

    private static Uni<Integer> execute(String query, Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(query).setParameter("id", id).executeUpdate());
    }
}
