package io.keydra.schedule.job;

import io.keydra.connections.service.ConnectionService;
import io.keydra.keys.dto.PurgeKeysRequest;
import io.keydra.keys.service.KeyService;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.schedule.exception.ScheduleRefusedException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Empties a database on a cadence.
 *
 * <p>Through the same purge the key browser uses — a cursor and batched deletes — rather than
 * FLUSHDB. The difference matters at three in the morning: FLUSHDB blocks the server for as long as
 * it takes, and a cache large enough to be worth emptying on a schedule is large enough for that to
 * be an outage. It also means a pattern can be given, which is what most people actually want and
 * what a FLUSHDB script cannot do.
 */
@ApplicationScoped
public class FlushDatabaseJob implements JobHandler {

    private final KeyService keys;
    private final ConnectionService connections;

    @Inject
    FlushDatabaseJob(KeyService keys, ConnectionService connections) {
        this.keys = keys;
        this.connections = connections;
    }

    @Override
    public JobType handles() {
        return JobType.FLUSH_DATABASE;
    }

    @Override
    public void check(ScheduledJob job) {
        JobSettings settings = JobSettings.of(job.settings, job.name);
        String match = settings.optional("match", "*");
        if (match.isBlank()) {
            throw new ScheduleRefusedException(
                    job.name + " has an empty pattern; use * to mean everything");
        }
    }

    @Override
    public Uni<String> run(ScheduledJob job) {
        JobSettings settings = JobSettings.of(job.settings, job.name);
        String match = settings.optional("match", "*");
        Integer database =
                settings.optionalNumber("database", -1) < 0
                        ? null
                        : settings.optionalNumber("database", 0);

        /*
         * The target's own name, supplied by the job rather than asked for — and the approved path
         * rather than the asking one, for the same reason.
         *
         * <p>Not a hole in either guard: a schedule that would empty a target which asks to be
         * named, or which asks for two people, is answered when it is written. That is where the
         * intent is. Nobody is present at three in the morning to type a name or to agree, and a
         * job that raised a request then would be a schedule that looked arranged and quietly
         * turned into a row nobody answered before it expired.
         */
        return connections
                .load(job.connectionId)
                .flatMap(
                        profile ->
                                keys.purgeApproved(
                                        job.connectionId,
                                        database,
                                        new PurgeKeysRequest(match, null, profile.name)))
                .map(result -> "Removed " + result.affected() + " keys matching " + match);
    }
}
