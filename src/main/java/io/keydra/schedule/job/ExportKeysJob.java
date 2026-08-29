package io.keydra.schedule.job;

import io.keydra.backup.service.BackupService;
import io.keydra.schedule.entity.JobType;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.schedule.exception.ScheduleRefusedException;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Backs a target up, somewhere that is not this machine.
 *
 * <p>Phase 10 wrote a file next to the application, which is a backup of the data and not a backup
 * of anything: the one failure a backup exists for is the machine going away, and that one went
 * with it. The job now names a destination — a local directory, a bucket, an SFTP drop, an FTP
 * server — and a local directory is one of the four rather than the only thing there is.
 *
 * <p>Everything hard is in {@link BackupService}: the keyspace is streamed through gzip onto a
 * staging file and sent from there, so nothing is held in memory and S3 gets the content length it
 * will not take a body without.
 */
@ApplicationScoped
public class ExportKeysJob implements JobHandler {

    private final BackupService backups;

    @Inject
    ExportKeysJob(BackupService backups) {
        this.backups = backups;
    }

    @Override
    public JobType handles() {
        return JobType.EXPORT_KEYS;
    }

    @Override
    public void check(ScheduledJob job) {
        JobSettings settings = JobSettings.of(job.settings, job.name);
        // Refused here rather than at three in the morning. A backup job with nowhere to send
        // the backup is the one misconfiguration that looks like it worked.
        settings.requiredNumber("destinationId");

        String prefix = settings.optional("filePrefix", "");
        // The prefix becomes a file name, and a name is not a way to write outside where the
        // destination points.
        if (prefix.contains("/") || prefix.contains("\\") || prefix.contains("..")) {
            throw new ScheduleRefusedException("A file name cannot contain a path");
        }
    }

    @Override
    public Uni<String> run(ScheduledJob job) {
        JobSettings settings = JobSettings.of(job.settings, job.name);
        int keepLast = settings.optionalNumber("keepLast", 0);

        return backups.take(
                        job.connectionId,
                        settings.requiredNumber("destinationId"),
                        settings.optional("filePrefix", null),
                        settings.optional("match", "*"),
                        keepLast <= 0 ? null : keepLast)
                .map(
                        taken ->
                                "Wrote "
                                        + taken.keys()
                                        + " keys to "
                                        + taken.name()
                                        + " on "
                                        + taken.destination()
                                        + (taken.removed().isEmpty()
                                                ? ""
                                                : ", removing "
                                                        + taken.removed().size()
                                                        + " older"));
    }
}
