package io.keydra.console.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One line typed into the console.
 *
 * @param id echoed back on the result, so a client that sent several lines can tell the replies
 *     apart without assuming they return in order
 * @param line the command exactly as typed, unparsed
 */
@Schema(name = "ConsoleCommand", description = "A command line to run")
public record ConsoleCommand(String id, String line) {}
