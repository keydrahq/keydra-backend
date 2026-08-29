package io.keydra.schedule.service;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.service.Approvals;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.persistence.ConnectionProfileRepository;
import io.keydra.connections.service.GuardedTargets;
import io.keydra.schedule.approval.ScheduleApprovalPayloads.ScheduleWritePayload;
import io.keydra.schedule.dto.ScheduleDtos.JobRunSummary;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleRequest;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleSummary;
import io.keydra.schedule.entity.JobRun;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.schedule.exception.ScheduleRefusedException;
import io.keydra.schedule.job.JobSettings;
import io.keydra.schedule.persistence.ScheduleRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Making and changing the work that happens on its own.
 *
 * <p>Everything is checked before it is stored: the cron by the scheduler that will run it, the
 * settings by the handler that will read them. A schedule refused here is one somebody can fix
 * while they are looking at it; the same schedule accepted and refused at three in the morning is a
 * line in a log nobody reads until the thing it fed is empty.
 */
@ApplicationScoped
public class ScheduleService {

    /** How many attempts a history shows. Enough to see a pattern, not enough to be a table. */
    private static final int RUN_HISTORY = 100;

    private final ScheduleRepository repository;
    private final ConnectionProfileRepository connections;
    private final JobScheduler scheduler;
    private final JobRunner runner;
    private final SecurityIdentity identity;
    private final CallerPermissions caller;
    private final Approvals approvals;

    @Inject
    ScheduleService(
            ScheduleRepository repository,
            ConnectionProfileRepository connections,
            JobScheduler scheduler,
            JobRunner runner,
            SecurityIdentity identity,
            CallerPermissions caller,
            Approvals approvals) {
        this.repository = repository;
        this.connections = connections;
        this.scheduler = scheduler;
        this.runner = runner;
        this.identity = identity;
        this.caller = caller;
        this.approvals = approvals;
    }

    /**
     * Every schedule the caller can see.
     *
     * <p>Filtered the same way the catalog is, and for the same reason: a schedule is about a
     * target, and a target somebody cannot reach is one whose arrangements are none of their
     * business. Visibility is the filter rather than a permission of its own — which is why this
     * endpoint requires nothing and still shows nobody anything they should not have.
     */
    @WithSession
    public Uni<List<ScheduleSummary>> list() {
        return repository.all().flatMap(this::onlyVisible).flatMap(this::describe);
    }

    private Uni<List<ScheduledJob>> onlyVisible(List<ScheduledJob> jobs) {
        return caller.visible(jobs.stream().map(job -> job.connectionId).distinct().toList())
                .map(
                        visible ->
                                jobs.stream()
                                        .filter(job -> visible.contains(job.connectionId))
                                        .toList());
    }

    @WithTransaction
    public Uni<ScheduleSummary> create(ScheduleRequest request) {
        return write(
                request, identity.isAnonymous() ? null : identity.getPrincipal().getName(), true);
    }

    /**
     * The same, on an arrangement a second person has already agreed to.
     *
     * <p>Called by nothing but the approvals runner. The author is whoever asked rather than
     * whoever agreed: a schedule runs with its author's access, and the person who approved it
     * agreed to somebody else's arrangement rather than making one of their own.
     */
    @WithTransaction
    public Uni<ScheduleSummary> createApproved(ScheduleRequest request, String requestedBy) {
        return write(request, requestedBy, false);
    }

    /**
     * @param ask whether there is somebody making this request to ask about. False on the approval
     *     path, where there is not: the work runs on a context of its own, hours later, and asking
     *     who is signed in there is a question with no answer. It is not a check skipped — {@code
     *     ApprovalService} asked the approver and {@code ApprovalGuard} asked the requester, which
     *     is the same question put to the two people who actually decided rather than to whoever
     *     happens to be on a request.
     */
    private Uni<ScheduleSummary> write(ScheduleRequest request, String author, boolean ask) {
        ScheduledJob job = new ScheduledJob();
        apply(job, request);
        job.createdBy = author;

        check(job);
        return namedWhereGuarded(job, request)
                .flatMap(
                        ignored ->
                                ask
                                        ? agreedWhereAsked(job, request, null)
                                        : Uni.createFrom().voidItem())
                .flatMap(ignored -> ask ? allowedToArrange(job) : Uni.createFrom().voidItem())
                .flatMap(ignored -> repository.save(job))
                .invoke(scheduler::register)
                .flatMap(saved -> describe(List.of(saved)))
                .map(described -> described.get(0));
    }

    @WithTransaction
    public Uni<ScheduleSummary> update(Long id, ScheduleRequest request) {
        return change(id, request, true);
    }

    /** The same change, on one a second person has already agreed to. */
    @WithTransaction
    public Uni<ScheduleSummary> updateApproved(Long id, ScheduleRequest request) {
        return change(id, request, false);
    }

    private Uni<ScheduleSummary> change(Long id, ScheduleRequest request, boolean ask) {
        return repository
                .byId(id)
                .flatMap(
                        job -> {
                            if (job == null) {
                                return Uni.createFrom()
                                        .failure(new ScheduleRefusedException("No such schedule"));
                            }
                            apply(job, request);
                            check(job);
                            return namedWhereGuarded(job, request)
                                    .flatMap(
                                            ignored ->
                                                    ask
                                                            ? agreedWhereAsked(job, request, id)
                                                            : Uni.createFrom().voidItem())
                                    .flatMap(
                                            ignored ->
                                                    ask
                                                            ? allowedToArrange(job)
                                                            : Uni.createFrom().voidItem())
                                    .invoke(() -> scheduler.register(job))
                                    .flatMap(ignored -> describe(List.of(job)))
                                    .map(described -> described.get(0));
                        });
    }

    /**
     * A schedule that would empty a guarded target names it when it is written.
     *
     * <p>Where the intent is. Nobody is present when a job fires, so a guard asked then would be a
     * schedule that looked arranged and silently refused itself every night — and a confirmation is
     * about somebody's intent at the moment of asking, which for a schedule is once.
     *
     * <p>Only the jobs that empty something. A schedule that takes a backup or samples a metric
     * reaches a guarded target too and takes nothing away from it, and asking would be how a guard
     * stops being read.
     *
     * <p>Both ends of a copy, which phase 59 left owing. It named the target a schedule runs
     * against and stopped there — so a nightly copy could overwrite a guarded server at the far end
     * without anybody ever typing its name, because the job supplies both names itself when it
     * fires. Found while building phase 60, and fixed here rather than left: two guards over one
     * operation that disagree about it are worse than one, and the one that is wrong is the one
     * nobody reads.
     */
    private Uni<Void> namedWhereGuarded(ScheduledJob job, ScheduleRequest request) {
        if (job.jobType != JobType.FLUSH_DATABASE && job.jobType != JobType.COPY_KEYS) {
            return Uni.createFrom().voidItem();
        }
        return connections
                .findById(job.connectionId)
                .invoke(
                        profile ->
                                GuardedTargets.requireNamed(
                                        profile,
                                        request.confirmTarget(),
                                        "This schedule would run "
                                                + job.jobType
                                                + " against it, unattended, on "
                                                + job.cron))
                .flatMap(ignored -> destinationOf(job))
                .invoke(
                        destination ->
                                GuardedTargets.requireNamed(
                                        destination,
                                        request.confirmSecond(),
                                        "This schedule would write keys into it, unattended, on "
                                                + job.cron))
                .replaceWithVoid();
    }

    /**
     * A schedule that would empty a target asking for two people is itself the thing approved.
     *
     * <p>Without this the phase has a hole wide enough to walk through: somebody who may not purge
     * a target on their own would arrange a job that does it in two minutes and be asked nothing.
     *
     * <p>What is approved is the arrangement, and approving it is what creates the schedule.
     * Nothing exists in the meantime — not a disabled row, not a draft — because a schedule that
     * exists and does not run is one somebody finds later and turns on. The firing is not approved
     * again, for the reason the naming is not asked again: nobody is present at three in the
     * morning, and a job that refused itself then would be an arrangement that looked made and
     * silently never ran.
     *
     * <p>Both ends of a copy, because a nightly copy into a target that asks for two people is a
     * way into it as much as a purge is.
     */
    private Uni<Void> agreedWhereAsked(ScheduledJob job, ScheduleRequest request, Long existingId) {
        if (job.jobType != JobType.FLUSH_DATABASE && job.jobType != JobType.COPY_KEYS) {
            return Uni.createFrom().voidItem();
        }
        Object payload = new ScheduleWritePayload(existingId, request);
        return connections
                .findById(job.connectionId)
                .flatMap(
                        target ->
                                destinationOf(job)
                                        .flatMap(
                                                destination ->
                                                        approvals.require(
                                                                target,
                                                                destination,
                                                                ApprovalKind.SCHEDULE_WRITE,
                                                                payload)));
    }

    /**
     * Where a copy would write, or nothing.
     *
     * <p>Unreadable settings answer nothing rather than failing here. The handler checks them a
     * moment later and says what is wrong with them, which is a better error than one about a
     * target that could not be looked up.
     */
    private Uni<ConnectionProfile> destinationOf(ScheduledJob job) {
        if (job.jobType != JobType.COPY_KEYS) {
            return Uni.createFrom().nullItem();
        }
        try {
            return connections.findById(
                    JobSettings.of(job.settings, job.name).requiredNumber("targetConnectionId"));
        } catch (RuntimeException unreadable) {
            return Uni.createFrom().nullItem();
        }
    }

    @WithTransaction
    public Uni<Boolean> delete(Long id) {
        scheduler.unregister(id);
        return repository.delete(id);
    }

    /**
     * Runs one now, because that is the first thing anybody does after writing a schedule.
     *
     * <p>Deliberately not in a session of its own: the runner opens and closes one per step so the
     * work in between holds nothing, and wrapping the whole call would put the work back inside a
     * transaction. The name for the answer is therefore read afterwards, in its own read.
     */
    public Uni<JobRunSummary> runNow(Long id) {
        return runner.run(id, true).flatMap(this::describeRun);
    }

    /** What the schedules the caller can see have done. Filtered the same way the list is. */
    @WithSession
    public Uni<List<JobRunSummary>> history(Long jobId) {
        return repository
                .all()
                .flatMap(this::onlyVisible)
                .flatMap(
                        jobs -> {
                            Map<Long, String> names = new HashMap<>();
                            jobs.forEach(job -> names.put(job.id, job.name));
                            return repository
                                    .runs(jobId, RUN_HISTORY)
                                    .map(
                                            runs ->
                                                    runs.stream()
                                                            // A run whose schedule is not in
                                                            // the visible set belongs to a
                                                            // target the caller cannot reach.
                                                            .filter(
                                                                    run ->
                                                                            names.containsKey(
                                                                                    run.jobId))
                                                            .map(run -> toSummary(run, names))
                                                            .toList());
                        });
    }

    /**
     * Checks what the job would need before it is stored.
     *
     * <p>Both halves: the scheduler decides what a cron expression means, and the handler decides
     * what its own settings mean. Checking either of them a second way here would be a second
     * opinion, and the one that matters is the one that will run.
     */
    private void check(ScheduledJob job) {
        scheduler.checkCron(job.cron);
        runner.handlerFor(job.jobType).check(job);
    }

    /**
     * The permissions these settings ask for beyond the schedule itself, checked as it is written.
     *
     * <p>{@code RunGuard} asks the same question at every firing and that is the one that matters;
     * this one is so nobody arranges something they will only find out was refused by reading the
     * failures. Two checks of one rule rather than a rule enforced twice: an annotation cannot
     * express it, because whether a copy needs {@code script:run} depends on what is in its
     * settings.
     *
     * <p>Asked about the job's target, which also answers about the instance — a grant made at
     * instance scope reaches every target, so a permission held over Keydra itself is in the set
     * that comes back.
     */
    private Uni<Void> allowedToArrange(ScheduledJob job) {
        return allowedOnTheTarget(job).flatMap(ignored -> alsoNeeded(job));
    }

    /**
     * {@code schedule:manage} on the target this job will actually run against.
     *
     * <p>Both surfaces already carry the annotation, and both read it from an argument beside the
     * request rather than from the request — which is the target the caller *says* it is about. A
     * caller holding the permission on one target could name that one in the argument and another
     * one in the body, and arrange work on a server they hold nothing on. The endpoints keep their
     * annotation as the coarse gate; this is the answer that decides.
     */
    private Uni<Void> allowedOnTheTarget(ScheduledJob job) {
        return caller.holds(Permission.SCHEDULE_MANAGE, job.connectionId)
                .flatMap(
                        held ->
                                Boolean.TRUE.equals(held)
                                        ? Uni.createFrom().voidItem()
                                        : Uni.createFrom()
                                                .failure(
                                                        new ScheduleRefusedException(
                                                                "Arranging work on that target"
                                                                        + " needs schedule:manage"
                                                                        + " on it")));
    }

    private Uni<Void> alsoNeeded(ScheduledJob job) {
        List<Permission> extra = List.copyOf(runner.handlerFor(job.jobType).alsoNeeds(job));
        if (extra.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return caller.forConnection(job.connectionId)
                .flatMap(
                        held -> {
                            Optional<Permission> missing =
                                    extra.stream().filter(one -> !held.contains(one)).findFirst();
                            return missing.isEmpty()
                                    ? Uni.createFrom().voidItem()
                                    : Uni.createFrom()
                                            .failure(
                                                    new ScheduleRefusedException(
                                                            "Arranging this needs "
                                                                    + missing.get().id()
                                                                    + " on this instance"));
                        });
    }

    private static void apply(ScheduledJob job, ScheduleRequest request) {
        job.name = request.name();
        job.connectionId = request.connectionId();
        job.jobType = request.jobType();
        job.cron = request.cron();
        job.enabled = request.enabled() == null || request.enabled();
        job.settings =
                request.settings() == null || request.settings().isBlank()
                        ? "{}"
                        : request.settings();
    }

    /** Schedules with the target's name beside them, so a list reads without a join. */
    private Uni<List<ScheduleSummary>> describe(List<ScheduledJob> jobs) {
        return connections
                .listAll()
                .map(
                        profiles -> {
                            Map<Long, String> names = new HashMap<>();
                            profiles.forEach(profile -> names.put(profile.id, profile.name));
                            return jobs.stream()
                                    .map(
                                            job ->
                                                    new ScheduleSummary(
                                                            job.id,
                                                            job.name,
                                                            job.connectionId,
                                                            names.getOrDefault(
                                                                    job.connectionId, "?"),
                                                            job.jobType,
                                                            job.cron,
                                                            job.enabled,
                                                            job.settings,
                                                            job.createdBy,
                                                            job.createdAt,
                                                            job.lastRunAt,
                                                            job.lastOutcome,
                                                            nextRun(job)))
                                    .toList();
                        });
    }

    /** When the scheduler expects to run this next, or nothing for one that is switched off. */
    private Instant nextRun(ScheduledJob job) {
        return job.enabled ? scheduler.nextRun(job.id) : null;
    }

    private Uni<JobRunSummary> describeRun(JobRun run) {
        return repository
                .forRun(run.jobId)
                .map(job -> toSummary(run, Map.of(run.jobId, job == null ? "?" : job.name)));
    }

    private static JobRunSummary toSummary(JobRun run, Map<Long, String> names) {
        return new JobRunSummary(
                run.id,
                run.jobId,
                names.getOrDefault(run.jobId, "?"),
                run.startedAt,
                run.finishedAt,
                run.outcome,
                run.detail,
                run.wasManual);
    }
}
