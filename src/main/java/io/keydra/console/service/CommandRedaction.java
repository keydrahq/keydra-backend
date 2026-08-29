package io.keydra.console.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Takes the secrets out of a command before it is written to the history.
 *
 * <p>The history stores lines exactly as typed, which is what makes the up arrow useful and what
 * makes a password typed into a console a password in the database. A deny-list of commands cannot
 * solve that on its own — {@code SET session:token abc} is an ordinary command whose argument is a
 * secret, and no policy can know that. What a policy <em>can</em> know is the small set of commands
 * whose arguments are secrets by definition, and those are masked here.
 *
 * <p>The command still runs. Only what is remembered about it changes: a person who has just
 * rotated a password should be able to see that they did, without the password being the thing that
 * proves it.
 */
public final class CommandRedaction {

    /** What a masked argument is replaced with. Short, obvious, and not a plausible value. */
    static final String MASK = "******";

    /** {@code CONFIG SET <these> <secret>}. */
    private static final Set<String> SECRET_SETTINGS =
            Set.of("requirepass", "masterauth", "masteruser", "primaryauth", "primaryuser");

    private CommandRedaction() {}

    /**
     * The line as it should be remembered.
     *
     * <p>Works on the parsed arguments rather than on the text, so quoting cannot hide a secret
     * from it: {@code CONFIG SET "requirepass" 'hunter2'} masks exactly as the unquoted form does.
     */
    public static String of(String line, List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return line;
        }
        String command = argv.get(0).toLowerCase(Locale.ROOT);
        List<String> safe =
                switch (command) {
                    // Everything after the command is the credential.
                    case "auth" -> maskFrom(argv, 1);
                    // HELLO [protover [AUTH username password] [SETNAME name]]
                    case "hello", "migrate" -> maskAfterKeyword(argv, "auth", "auth2");
                    case "config" -> maskConfigSet(argv);
                    case "acl" -> maskAclSetUser(argv);
                    default -> null;
                };
        return safe == null ? line : String.join(" ", safe);
    }

    /** Masks every argument from an index onwards. */
    private static List<String> maskFrom(List<String> argv, int from) {
        List<String> safe = new ArrayList<>(argv);
        for (int i = from; i < safe.size(); i++) {
            safe.set(i, MASK);
        }
        return safe;
    }

    /**
     * Masks whatever follows an {@code AUTH} keyword inside a longer command.
     *
     * <p>{@code MIGRATE} and {@code HELLO} both carry credentials that way, and both have other
     * arguments worth keeping — a MIGRATE whose destination is masked would be a history entry that
     * says nothing.
     */
    private static List<String> maskAfterKeyword(List<String> argv, String... keywords) {
        Set<String> markers = Set.of(keywords);
        List<String> safe = new ArrayList<>(argv);
        boolean found = false;
        for (int i = 1; i < safe.size(); i++) {
            if (markers.contains(safe.get(i).toLowerCase(Locale.ROOT))) {
                found = true;
                continue;
            }
            if (found) {
                safe.set(i, MASK);
            }
        }
        return found ? safe : null;
    }

    /** {@code CONFIG SET requirepass <secret>} and its relatives; other settings are left alone. */
    private static List<String> maskConfigSet(List<String> argv) {
        if (argv.size() < 4 || !"set".equalsIgnoreCase(argv.get(1))) {
            return null;
        }
        List<String> safe = new ArrayList<>(argv);
        boolean masked = false;
        // CONFIG SET takes name/value pairs since Redis 7, so every pair is examined.
        for (int i = 2; i + 1 < safe.size(); i += 2) {
            if (SECRET_SETTINGS.contains(safe.get(i).toLowerCase(Locale.ROOT))) {
                safe.set(i + 1, MASK);
                masked = true;
            }
        }
        return masked ? safe : null;
    }

    /**
     * {@code ACL SETUSER alice on >hunter2 ~key:* +get}.
     *
     * <p>The password rules are the ones that start with a marker character: {@code >} sets one,
     * {@code <} removes one, {@code #} and {@code !} give and remove a hash. Everything else is
     * what the user may do, which is the part worth remembering.
     */
    private static List<String> maskAclSetUser(List<String> argv) {
        if (argv.size() < 3 || !"setuser".equalsIgnoreCase(argv.get(1))) {
            return null;
        }
        List<String> safe = new ArrayList<>(argv);
        boolean masked = false;
        for (int i = 3; i < safe.size(); i++) {
            String rule = safe.get(i);
            if (!rule.isEmpty() && ">?<?#?!?".indexOf(rule.charAt(0)) >= 0 && isSecretRule(rule)) {
                safe.set(i, rule.charAt(0) + MASK);
                masked = true;
            }
        }
        return masked ? safe : null;
    }

    private static boolean isSecretRule(String rule) {
        char marker = rule.charAt(0);
        return marker == '>' || marker == '<' || marker == '#' || marker == '!';
    }
}
