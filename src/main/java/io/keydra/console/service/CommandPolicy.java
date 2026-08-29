package io.keydra.console.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.exception.InvalidConnectionException;
import io.keydra.console.exception.CommandNotAllowedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decides which commands the console may run.
 *
 * <p>Three kinds are refused, for three different reasons.
 *
 * <p><b>Blocking commands</b> — {@code BLPOP}, {@code MONITOR}, {@code SUBSCRIBE} and their
 * relatives — do not return. The connection they run on is pooled and shared, so one of these would
 * take the whole application's connection to that target with it, not just the console's.
 *
 * <p><b>Whole-keyspace commands</b> — {@code KEYS}, {@code FLUSHALL}, {@code FLUSHDB} — block the
 * server itself for as long as the keyspace is large. That is the rule the whole project is built
 * on: the browser exists because {@code KEYS} is not an acceptable thing to run.
 *
 * <p><b>Server-state commands</b> — {@code SHUTDOWN}, {@code REPLICAOF}, {@code DEBUG} — do
 * something to the target that no key browser should be able to do by accident.
 *
 * <p>Every entry is configurable: an operator who wants {@code FLUSHDB} on a scratch target can
 * have it, deliberately, in configuration rather than by surprise.
 */
@ApplicationScoped
public class CommandPolicy {

    /**
     * The half that is about Keydra, and is therefore never a target's to allow.
     *
     * <p>Written out rather than derived from a category flag in the server's COMMAND table,
     * because that table describes what a command does, not what it would do to a shared pooled
     * connection in this application.
     *
     * <p>Not askable. {@code BLPOP} on a shared connection breaks this application whoever owns the
     * server on the other end, and {@code SELECT} leaves that connection pointing at another
     * database for whoever uses it next. None of that is a judgement about a target, so none of it
     * is a judgement a target gets to overturn: an allowance here would be a way of configuring a
     * fault rather than a way of widening a policy.
     */
    private static final Set<String> ABOUT_KEYDRA =
            Set.of(
                    // Never return, and the connection is shared.
                    "subscribe",
                    "psubscribe",
                    "ssubscribe",
                    "monitor",
                    "blpop",
                    "brpop",
                    "blmove",
                    "blmpop",
                    "brpoplpush",
                    "bzpopmin",
                    "bzpopmax",
                    "bzmpop",
                    "wait",
                    // Would leave the pooled connection pointing somewhere else, or wearing
                    // somebody else's identity.
                    "select",
                    "hello",
                    // Puts the connection back to a state nobody who shares it asked for: no
                    // database selected, no authentication, out of whatever mode it was in.
                    "reset",
                    // Re-authenticates a connection this application shares between everybody
                    // using the target, so one person's AUTH becomes everybody's identity —
                    // the same class of problem as SELECT and HELLO. That it would also put a
                    // password in the console history is the second reason, not the first.
                    "auth");

    /*
     * Why each of those is refused.
     *
     * <p>A key rather than the sentence. Until this was a key the sentence itself travelled to the
     * browser, which made the one screen where somebody decides whether to allow MODULE on a target
     * the one screen in the application that is English whatever language it is asked for. The
     * sentences now live beside every other piece of interface text, in the frontend's locale files
     * under {@code Console.REASON_*}, and this end sends the fact.
     *
     * <p>Constants rather than string literals in the map below, so a typo in one of thirty-two
     * entries is a compile error rather than a group of one on a form.
     */
    private static final String BLOCKS_THE_SERVER = "blocks-the-server";

    private static final String CHANGES_THE_SERVER = "changes-the-server";

    private static final String WRITES_A_FILE = "writes-a-file";

    private static final String RUNS_CODE = "runs-code";

    private static final String DESERIALISES = "deserialises";

    private static final String COPIES_ELSEWHERE = "copies-elsewhere";

    private static final String CREATES_AN_IDENTITY = "creates-an-identity";

    private static final String CUTS_OTHERS_OFF = "cuts-others-off";

    private static final String REARRANGES_A_CLUSTER = "rearranges-a-cluster";

    private static final String REPLICATION_INTERNALS = "replication-internals";

    private static final String ERASES_EVIDENCE = "erases-evidence";

    /**
     * The half that is about the target, which one target may allow and its neighbour may not.
     *
     * <p>None of these is refused because it is dangerous in itself — a database administrator runs
     * all of them. They are refused because the console is reached by a role that stops short of
     * them, and an installation where that role is the administrator, or a scratch server where
     * none of it matters, says so on that profile.
     *
     * <p>Running the console is {@code console:run}, which an operator holds. An operator is
     * somebody trusted to change the data — deliberately not somebody trusted to change what Keydra
     * points at, which is why editing a connection is a different permission. But {@code CONFIG SET
     * dir} with {@code CONFIG SET dbfilename} and a {@code SAVE} writes a file of the caller's
     * choosing anywhere the server process can write, and {@code MODULE LOAD} hands it a shared
     * object to run. Either one turns "may edit a value" into "may execute code on the machine the
     * server is on", which is not a step the role ladder anywhere else lets somebody take. {@code
     * MIGRATE} is the same mistake in the other direction: it copies keys to any host and port,
     * past every visibility rule and every audit entry.
     */
    private static final Map<String, String> ABOUT_THE_TARGET =
            java.util.Map.ofEntries(
                    reason("keys", BLOCKS_THE_SERVER),
                    reason("flushall", BLOCKS_THE_SERVER),
                    reason("flushdb", BLOCKS_THE_SERVER),
                    reason("swapdb", BLOCKS_THE_SERVER),
                    reason("shutdown", CHANGES_THE_SERVER),
                    reason("replicaof", CHANGES_THE_SERVER),
                    reason("slaveof", CHANGES_THE_SERVER),
                    reason("failover", CHANGES_THE_SERVER),
                    reason("debug", CHANGES_THE_SERVER),
                    reason("config", WRITES_A_FILE),
                    reason("save", WRITES_A_FILE),
                    reason("bgsave", WRITES_A_FILE),
                    reason("bgrewriteaof", WRITES_A_FILE),
                    reason("module", RUNS_CODE),
                    reason("eval", RUNS_CODE),
                    reason("eval_ro", RUNS_CODE),
                    reason("evalsha", RUNS_CODE),
                    reason("evalsha_ro", RUNS_CODE),
                    reason("fcall", RUNS_CODE),
                    reason("fcall_ro", RUNS_CODE),
                    reason("function", RUNS_CODE),
                    reason("script", RUNS_CODE),
                    reason("restore", DESERIALISES),
                    reason("migrate", COPIES_ELSEWHERE),
                    reason("acl", CREATES_AN_IDENTITY),
                    reason("client", CUTS_OTHERS_OFF),
                    reason("cluster", REARRANGES_A_CLUSTER),
                    reason("sync", REPLICATION_INTERNALS),
                    reason("psync", REPLICATION_INTERNALS),
                    reason("replconf", REPLICATION_INTERNALS),
                    reason("slowlog", ERASES_EVIDENCE));

    private static Map.Entry<String, String> reason(String command, String why) {
        return Map.entry(command, why);
    }

    /** Both halves, which is what a target that says nothing refuses. */
    private static final Set<String> DEFAULT_DENIED =
            java.util.stream.Stream.concat(
                            ABOUT_KEYDRA.stream(), ABOUT_THE_TARGET.keySet().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final Set<String> denied;

    @Inject
    CommandPolicy(
            @ConfigProperty(name = "keydra.console.denied-commands")
                    Optional<List<String>> configuredDenied,
            @ConfigProperty(name = "keydra.console.allowed-commands")
                    Optional<List<String>> configuredAllowed) {
        Set<String> effective =
                new LinkedHashSet<>(
                        configuredDenied
                                .map(CommandPolicy::normalise)
                                .orElse(new LinkedHashSet<>(DEFAULT_DENIED)));
        // An explicit allow-list removes entries from the deny-list rather than replacing it, so
        // permitting one command does not quietly permit the rest.
        configuredAllowed.map(CommandPolicy::normalise).ifPresent(effective::removeAll);
        this.denied = Set.copyOf(effective);
    }

    private static LinkedHashSet<String> normalise(List<String> names) {
        LinkedHashSet<String> normalised = new LinkedHashSet<>(names.size());
        names.forEach(name -> normalised.add(name.trim().toLowerCase(Locale.ROOT)));
        return normalised;
    }

    /** Throws when the command is refused on this target; returns quietly when it is not. */
    public void check(ConnectionProfile profile, List<String> argv) {
        if (argv.isEmpty()) {
            return;
        }
        String command = argv.get(0).toLowerCase(Locale.ROOT);
        if (deniedFor(profile).contains(command)) {
            throw new CommandNotAllowedException(
                    command, "The console does not run " + command.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * What this target refuses, so the interface can grey it out rather than let it fail.
     *
     * <p>The instance's list is the floor and a profile takes names out of it. Not the other way
     * round: an installation that is one team with a rack of scratch servers should not have to say
     * the same thing on forty profiles, and a per-target list that could only add would leave that
     * installation with nowhere to say it once.
     *
     * <p>What a profile cannot take out is the half about Keydra. That is refused when the profile
     * is saved, so by here the list has already been checked — this is the second door, closed for
     * the row that predates the check.
     */
    public Set<String> deniedFor(ConnectionProfile profile) {
        Set<String> allowed = profile == null ? Set.of() : allowedOn(profile);
        if (allowed.isEmpty()) {
            return denied;
        }
        Set<String> forThisTarget = new LinkedHashSet<>(denied);
        forThisTarget.removeAll(allowed);
        forThisTarget.addAll(ABOUT_KEYDRA);
        return Set.copyOf(forThisTarget);
    }

    /** The instance-wide list, for a caller with no target in hand. */
    public Set<String> denied() {
        return denied;
    }

    /** The commands a target may be allowed, which is the half that is about the target. */
    public static Set<String> askable() {
        return ABOUT_THE_TARGET.keySet();
    }

    /** The same commands with what each of them would do, for a form that offers the choice. */
    public static Map<String, String> askableWithReasons() {
        return ABOUT_THE_TARGET;
    }

    /**
     * Checks what a profile is asking to allow, and says which half a refusal is about.
     *
     * <p>Called where a profile is saved. Two ways of being wrong and they read differently: a name
     * nobody recognises is usually a typo, and a name from the other half is somebody asking for
     * something that cannot be given — so the second says why rather than saying "unknown".
     */
    public static void requireAskable(ConnectionProfile profile) {
        for (String command : allowedOn(profile)) {
            if (ABOUT_THE_TARGET.containsKey(command)) {
                continue;
            }
            if (ABOUT_KEYDRA.contains(command)) {
                throw new InvalidConnectionException(
                        command.toUpperCase(Locale.ROOT)
                                + " cannot be allowed on a target. It is refused because of what it"
                                + " would do to Keydra's own connection to the server, which is the"
                                + " same on every target.");
            }
            throw new InvalidConnectionException(
                    "The console does not refuse "
                            + command.toUpperCase(Locale.ROOT)
                            + ", so there is nothing to allow. Only the commands this target"
                            + " refuses can be named.");
        }
    }

    /** What a profile says it allows, normalised the way the deny-list is. */
    private static Set<String> allowedOn(ConnectionProfile profile) {
        if (profile == null || profile.consoleAllowed == null || profile.consoleAllowed.isBlank()) {
            return Set.of();
        }
        return normalise(List.of(profile.consoleAllowed.split(",")));
    }
}
