package io.keydra.schedule.job;

import io.keydra.authz.entity.Permission;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.keys.dto.MigrateKeysRequest;
import io.keydra.keys.service.KeyMigrationService;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.schedule.exception.ScheduleRefusedException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Copies keys to another target on a cadence.
 *
 * <p>The migration Keydra already does, started by the clock instead of by somebody. The same
 * service, so a nightly copy and a manual one are the same code, move keys the same way, and fail
 * for the same reasons — including the one nobody expects, where the two servers cannot serialise
 * for each other and the copy falls back to reading and writing values.
 *
 * <p>Answers as soon as the job is accepted rather than when it finishes: a migration is a job with
 * its own progress on the notification hub, and a schedule that waited for one would be a schedule
 * that appears to hang for an hour.
 */
@ApplicationScoped
public class CopyKeysJob implements JobHandler {

    private final KeyMigrationService migrations;
    private final ConnectionService connections;

    @Inject
    CopyKeysJob(KeyMigrationService migrations, ConnectionService connections) {
        this.connections = connections;
        this.migrations = migrations;
    }

    @Override
    public JobType handles() {
        return JobType.COPY_KEYS;
    }

    @Override
    public void check(ScheduledJob job) {
        JobSettings settings = JobSettings.of(job.settings, job.name);
        long target = settings.requiredNumber("targetConnectionId");
        if (target == job.connectionId) {
            throw new ScheduleRefusedException("A target cannot copy to itself");
        }
    }

    @Override
    public Uni<String> run(ScheduledJob job) {
        JobSettings settings = JobSettings.of(job.settings, job.name);
        Long destinationId = settings.requiredNumber("targetConnectionId");

        return connections
                .load(job.connectionId)
                .flatMap(
                        source ->
                                connections
                                        .load(destinationId)
                                        .flatMap(
                                                destination ->
                                                        copy(job, settings, source, destination)));
    }

    private Uni<String> copy(
            ScheduledJob job,
            JobSettings settings,
            ConnectionProfile source,
            ConnectionProfile destination) {
        return migrations
                .startApproved(
                        job.connectionId,
                        new MigrateKeysRequest(
                                destination.id,
                                null,
                                settings.optional("match", "*"),
                                // The narrowing and shaping a manual migration can ask for, asked
                                // for the same way here: a copy that runs at three in the morning
                                // is the one that most wants a ceiling on how hard it pulls, and
                                // the one nobody is watching to notice it did not have one.
                                settings.optional("type", null),
                                settings.optional("stripPrefix", null),
                                settings.optional("addPrefix", null),
                                // A script, on the terms `alsoNeeds` below sets: the permission to
                                // run one is resolved against whoever arranged the schedule at
                                // every firing, so a grant taken away between writing it and three
                                // in the morning stops it. Without that this would have been a way
                                // of keeping an access somebody had removed.
                                settings.optional("script", null),
                                zeroAsNull(settings.optionalNumber("maxKeysPerSecond", 0)),
                                settings.optionalFlag("replace", true),
                                settings.optionalFlag("deleteFromSource", false),
                                null,
                                /*
                                 * Both names, supplied by the job rather than asked for — and the
                                 * approved path rather than the asking one, for the same reason.
                                 *
                                 * <p>Not a hole in either guard: a schedule that would write into a
                                 * target which asks to be named, or which asks for two people, is
                                 * answered when it is written. Nobody is present at three in the
                                 * morning to type a name or to agree, and a job that raised a
                                 * request then would be a schedule that looked arranged and quietly
                                 * turned into a row nobody answered before it expired.
                                 */
                                destination.name,
                                source.name),
                        // Whoever arranged the schedule, which is the honest answer to "who
                        // started this copy" — the clock started it, on their behalf.
                        job.createdBy)
                .map(started -> "Started a copy to connection " + started.targetConnectionId());
    }

    /**
     * A script makes this two things at once, and the second is not about the target.
     *
     * <p>{@code copy:run} is about moving keys off this server. A script runs inside Keydra, on
     * every key, for as long as the walk lasts — that is {@code script:run} on the instance, and it
     * is asked for as well as, never instead of, the permission the copy already needs.
     *
     * <p>Unreadable settings ask for nothing. The run refuses them a moment later with a sentence
     * saying so, which is a better error than one about a permission.
     */
    @Override
    public Set<Permission> alsoNeeds(ScheduledJob job) {
        try {
            String script = JobSettings.of(job.settings, job.name).optional("script", null);
            return script == null || script.isBlank() ? Set.of() : Set.of(Permission.SCRIPT_RUN);
        } catch (ScheduleRefusedException unreadable) {
            return Set.of();
        }
    }

    /** An unset ceiling is no ceiling, which the request spells as null rather than as zero. */
    private static Integer zeroAsNull(int value) {
        return value <= 0 ? null : value;
    }
}
