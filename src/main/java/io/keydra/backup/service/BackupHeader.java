package io.keydra.backup.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The first line of a backup file: what it is and where it came from.
 *
 * <p>Inside the file rather than only in its name, because restoring into the wrong target is
 * exactly the mistake this feature makes easy to make. A restore dialog that can say "this was
 * taken from payments-cache" can also say so when the target chosen is a different one.
 *
 * <p>{@code keydra} is a format version and is what a reader recognises the header by. A file whose
 * first line has no such field is read as a file of keys with no header at all — which means a
 * hand-made NDJSON file still restores, and that is worth keeping true.
 *
 * @param keydra the format version, 1
 * @param connection the target's name when it was taken, for a person to read
 * @param connectionId the target's id, so an interface can offer to restore into the same one
 * @param takenAt when the export started
 * @param match the pattern it was taken with, so a partial backup does not look like a whole one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "BackupHeader", description = "What a backup file is and where it came from")
public record BackupHeader(
        Integer keydra, String connection, Long connectionId, Instant takenAt, String match) {

    /** The only version there is. A file claiming a newer one is refused rather than guessed at. */
    public static final int VERSION = 1;

    public static BackupHeader of(String connection, Long connectionId, String match) {
        return new BackupHeader(VERSION, connection, connectionId, Instant.now(), match);
    }
}
