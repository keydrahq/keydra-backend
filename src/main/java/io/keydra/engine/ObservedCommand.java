package io.keydra.engine;

import java.util.List;

/**
 * One command a target executed, as the store reported it.
 *
 * <p>Parsed rather than passed on as a line, because the line is the store's own formatting and
 * everything worth doing with it — filtering by command, by client, by database — needs the parts.
 *
 * @param atMicros when the store executed it, in microseconds since the epoch; a busy server runs
 *     thousands of commands a second and a whole second is not enough to order them
 * @param database the database index it ran against, or -1 when the store did not say
 * @param client the address it came from, or null for a command the store issued itself
 * @param name the command, upper-cased, as the store names it
 * @param arguments what followed the command, in order — with anything secret already removed
 */
public record ObservedCommand(
        long atMicros, int database, String client, String name, List<String> arguments) {}
