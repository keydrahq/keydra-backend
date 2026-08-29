package io.keydra.engine;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A command the store recorded as slow.
 *
 * @param id the store's own identifier, used to page through the log without repeats
 * @param at when it ran
 * @param durationMicros how long it took, in microseconds — the unit the store reports
 * @param arguments the command and its arguments, possibly abbreviated by the store
 * @param client the address it came from, when the store records one
 * @param clientName the name the client gave itself, when it gave one
 */
@Schema(name = "SlowCommand", description = "A command the server recorded as slow")
public record SlowCommand(
        long id,
        Instant at,
        long durationMicros,
        List<String> arguments,
        String client,
        String clientName) {}
