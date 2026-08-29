package io.keydra.console.dto;

import io.keydra.engine.ConsoleValue;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What one command line produced.
 *
 * @param id the id the command carried
 * @param line the line as typed, so a reconnecting client can rebuild the transcript
 * @param value the reply, including an error reply the server returned
 * @param durationMs how long the round trip took, which is most of why anyone opens a console
 */
@Schema(name = "ConsoleResult", description = "The reply to one command line")
public record ConsoleResult(String id, String line, ConsoleValue value, long durationMs) {

    public static ConsoleResult failed(String id, String line, String message) {
        return new ConsoleResult(id, line, new ConsoleValue.Failure(message), 0);
    }
}
