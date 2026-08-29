package io.keydra.engine;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How a server is keeping its data, and when it last managed to.
 *
 * <p>The two figures worth acting on are the last successful save and whether the last attempt
 * failed: a server that has been unable to write its snapshot for a day is one restart away from
 * losing everything since, and it will not say so anywhere a person would look.
 *
 * @param snapshotEnabled whether the server is configured to write snapshots at all
 * @param logEnabled whether the append-only log is on
 * @param lastSaveSeconds when the last snapshot succeeded, in seconds since the epoch
 * @param lastSaveFailed whether the last attempt failed
 * @param changesSinceSave how many writes have happened since the last snapshot
 * @param inProgress whether a snapshot or a rewrite is running right now
 * @param snapshotFile where the server writes its snapshot, as an absolute path on the server's own
 *     filesystem — which for a container is inside the container. The most common question after
 *     pressing "snapshot now" is where the file went, and the answer is not somewhere Keydra can
 *     reach: a snapshot is the server saving its own memory to its own disk. Null when the server
 *     declines to say.
 */
@Schema(name = "PersistenceState", description = "How a server is keeping its data")
public record PersistenceState(
        boolean snapshotEnabled,
        boolean logEnabled,
        long lastSaveSeconds,
        boolean lastSaveFailed,
        long changesSinceSave,
        boolean inProgress,
        String snapshotFile) {}
