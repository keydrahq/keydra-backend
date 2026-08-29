package io.keydra.backup.store;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One backup that is already somewhere.
 *
 * <p>Three things, because three is what every one of the four kinds can answer without opening the
 * file: what it is called, how big it is, and when it was written. Anything more — which target it
 * came from, how many keys are in it — is inside, and a listing that had to open every file to draw
 * a table would be a listing nobody waits for.
 *
 * @param name the file's name within the destination, which is also how it is asked for back
 * @param size in bytes
 * @param modifiedAt when the destination says it was written; null where it will not say
 */
@Schema(name = "StoredBackup", description = "A backup already in a destination")
public record StoredBackup(String name, long size, Instant modifiedAt) {}
