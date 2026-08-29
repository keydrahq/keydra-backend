package io.keydra.console.service;

import io.keydra.authz.service.CallerPermissions;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.console.dto.AskableCommand;
import io.keydra.console.dto.ConsoleCommand;
import io.keydra.console.dto.ConsoleResult;
import io.keydra.console.dto.HistoryEntry;
import io.keydra.console.exception.CommandNotAllowedException;
import io.keydra.console.exception.MalformedCommandException;
import io.keydra.engine.CommandConsole;
import io.keydra.engine.ConsoleValue;
import io.keydra.engine.EngineSelector;
import io.keydra.security.service.AuditService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * Runs command lines against a target and remembers them.
 *
 * <p>A refused command still produces a result rather than an exception: the console's job is to
 * show what happened, and "the console does not run FLUSHALL" is an answer the user needs to read
 * in the transcript, in place, next to the line that caused it.
 */
@ApplicationScoped
public class ConsoleService {

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final CommandParser parser;
    private final CommandPolicy policy;
    private final ConsoleHistoryService history;
    private final CallerPermissions caller;
    private final AuditService audit;

    @Inject
    ConsoleService(
            ConnectionService connections,
            EngineSelector engines,
            CommandParser parser,
            CommandPolicy policy,
            ConsoleHistoryService history,
            CallerPermissions caller,
            AuditService audit) {
        this.connections = connections;
        this.engines = engines;
        this.parser = parser;
        this.policy = policy;
        this.history = history;
        this.caller = caller;
        this.audit = audit;
    }

    /**
     * Parses, checks and runs one line.
     *
     * <p>Only lines that were actually sent to the server are recorded. A typo that failed to parse
     * is not worth recalling with the up arrow, and a command refused by policy would put a line in
     * the history that can never be run.
     */
    public Uni<ConsoleResult> run(Long connectionId, ConsoleCommand command) {
        List<String> argv;
        try {
            argv = parser.parse(command.line());
        } catch (MalformedCommandException e) {
            return Uni.createFrom()
                    .item(ConsoleResult.failed(command.id(), command.line(), e.getMessage()));
        }
        if (argv.isEmpty()) {
            return Uni.createFrom()
                    .item(
                            new ConsoleResult(
                                    command.id(), command.line(), new ConsoleValue.Nil(), 0));
        }

        long started = System.nanoTime();
        /*
         * Whether a command is refused is now a question about this target, so it cannot be
         * answered until the profile is in hand. The refusal is still a result rather than a
         * failure — the console's job is to show what happened, and "this target does not run
         * FLUSHDB" is a line to read — and it is answered before anything below, so a refused
         * command still never reaches the history or the audit log.
         *
         * The profile is loaded once and carried, rather than loaded for the check and again for
         * the command. Two round trips per console line, for a row that cannot have changed in
         * between, is a cost paid on every keystroke somebody presses enter on.
         */
        return connections
                .load(connectionId)
                .flatMap(
                        profile -> {
                            try {
                                policy.check(profile, argv);
                            } catch (CommandNotAllowedException notAllowed) {
                                return Uni.createFrom()
                                        .item(
                                                ConsoleResult.failed(
                                                        command.id(),
                                                        command.line(),
                                                        notAllowed.getMessage()));
                            }
                            return ran(connectionId, profile, command, argv, started);
                        });
    }

    /** One command that this target allows, run and written down. */
    private Uni<ConsoleResult> ran(
            Long connectionId,
            ConnectionProfile profile,
            ConsoleCommand command,
            List<String> argv,
            long started) {
        return execute(profile, argv)
                .map(
                        value ->
                                new ConsoleResult(
                                        command.id(),
                                        command.line(),
                                        value,
                                        (System.nanoTime() - started) / 1_000_000))
                // Written down twice, in two places, for two different readers.
                //
                // The history is the person's own up arrow: theirs, on this target, and theirs to
                // clear. The audit entry is the record that a command was run against somebody's
                // server, and it is not the runner's to remove — which is the whole difference
                // between them. Running a command was the most powerful thing this product does
                // and the only one of them that left no trace anybody else could read.
                //
                // Both hold the line with its secrets taken out. A console holds whatever anybody
                // typed, and a password typed into one would otherwise be a password in the
                // database in the clear — readable by anyone who can read a backup of it, and
                // outliving the rotation it was part of.
                //
                // Recorded through other beans: @WithTransaction is an interceptor, and a bean
                // calling its own method does not go through one.
                .call(
                        () -> {
                            String redacted = CommandRedaction.of(command.line(), argv);
                            return caller.currentUserId()
                                    .flatMap(
                                            userId ->
                                                    history.record(
                                                            connectionId,
                                                            userId,
                                                            redacted,
                                                            Instant.now()))
                                    .call(
                                            () ->
                                                    audit.record(
                                                            "console.run",
                                                            connectionId,
                                                            redacted,
                                                            true));
                        });
    }

    private Uni<ConsoleValue> execute(ConnectionProfile profile, List<String> argv) {
        return engines.forProfile(profile)
                .console()
                .map(console -> run(console, profile, argv))
                .orElseGet(
                        () ->
                                Uni.createFrom()
                                        .item(
                                                new ConsoleValue.Failure(
                                                        "This target has no command console")));
    }

    private Uni<ConsoleValue> run(
            CommandConsole console, ConnectionProfile profile, List<String> argv) {
        return console.execute(profile, argv);
    }

    /**
     * The commands a target can be allowed, with what each of them would do.
     *
     * <p>Sorted by the reason first and the command second, so a form drawing them in order gets
     * the groups without having to know what the groups are. By the reason's key rather than by its
     * sentence, which is what keeps the groups in the same order in every language.
     */
    public List<AskableCommand> askableCommands() {
        return CommandPolicy.askableWithReasons().entrySet().stream()
                .map(entry -> new AskableCommand(entry.getKey(), entry.getValue()))
                .sorted(
                        java.util.Comparator.comparing(AskableCommand::reason)
                                .thenComparing(AskableCommand::command))
                .toList();
    }

    public Uni<List<HistoryEntry>> history(Long connectionId) {
        return caller.currentUserId().flatMap(userId -> history.recent(connectionId, userId));
    }

    public Uni<Long> clearHistory(Long connectionId) {
        return caller.currentUserId().flatMap(userId -> history.clear(connectionId, userId));
    }

    /**
     * The commands the console refuses on one target, so a client can say so before the round trip.
     *
     * <p>Per target since phase 58. The endpoint has always been addressed to one — the id has been
     * in its path since it was written — and until a profile could widen the list there was nothing
     * for it to read the id for.
     */
    public Uni<List<String>> deniedCommands(Long connectionId) {
        return connections
                .load(connectionId)
                .map(profile -> policy.deniedFor(profile).stream().sorted().toList());
    }
}
