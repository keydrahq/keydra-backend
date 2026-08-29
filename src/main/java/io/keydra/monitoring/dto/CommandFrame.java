package io.keydra.monitoring.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One command a target executed, or a note about the watch itself.
 *
 * <p>The two travel on the same socket because a reader who has been shown ten thousand commands
 * needs to be told when some were not shown, in the same place and the same order — a count sent
 * somewhere else is a count nobody relates to what they were reading.
 *
 * @param atMicros when the store executed it, in microseconds since the epoch
 * @param database the database it ran against, or -1 when the store did not say
 * @param client where it came from, or null for a command the store issued itself
 * @param name the command, upper-cased
 * @param arguments what followed it, with anything secret already removed
 * @param dropped how many commands were discarded before this one because the reader was behind;
 *     zero on every frame that follows one the reader kept up with
 */
@Schema(name = "CommandFrame", description = "One observed command, or a note about the watch")
public record CommandFrame(
        long atMicros,
        int database,
        String client,
        String name,
        List<String> arguments,
        long dropped) {}
