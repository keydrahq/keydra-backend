package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Python's pickle — identified and described, never unpickled.
 *
 * <p>Unpickling calls whatever the stream names: the format has an opcode whose entire job is
 * "import this module and call this function", which is why the Python documentation says not to
 * unpickle data from an untrusted source. A value read out of somebody's Redis is that source. So
 * this reads the imports the stream declares and stops.
 *
 * <p>Naming what it would have imported is the useful part anyway: a pickle in a cache is nearly
 * always one known type, and seeing which one is what somebody browsing wanted to know.
 */
@ApplicationScoped
public class PickleDecoder implements ValueDecoder {

    public static final String ID = "pickle";

    /** PROTO: the first opcode of every protocol 2+ pickle, followed by the version. */
    private static final byte PROTO = (byte) 0x80;

    /** STOP: the last opcode of every pickle, whatever its protocol. */
    private static final byte STOP = '.';

    /** GLOBAL and STACK_GLOBAL: "import this module, take this name from it". */
    private static final byte GLOBAL = 'c';

    /** The highest protocol this recognises; anything beyond it is not a pickle we know. */
    private static final int MAX_PROTOCOL = 5;

    private static final int MAX_IMPORTS = 32;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        // Two distinctive bytes at the front and one at the back; specific enough to sit
        // beside gzip rather than below the compression guesses.
        return 12;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < 3 || raw[raw.length - 1] != STOP) {
            return false;
        }
        // Protocol 2 and later announce themselves; earlier ones are plain ASCII opcodes
        // and too weak to claim, so they are left to the text decoders.
        return raw[0] == PROTO && (raw[1] & 0xff) <= MAX_PROTOCOL;
    }

    @Override
    public String decode(byte[] raw) {
        List<String> imports = imports(raw);
        StringBuilder out = new StringBuilder("Python pickle, protocol ");
        out.append(raw[1] & 0xff).append(", ").append(raw.length).append(" bytes\n\n");
        if (imports.isEmpty()) {
            out.append("The stream imports nothing: it holds only built-in types.\n");
        } else {
            out.append("The stream would import:\n");
            imports.forEach(name -> out.append("  ").append(name).append('\n'));
        }
        out.append(
                "\n"
                        + "The value was not unpickled. Unpickling runs what the stream names — the"
                        + " format has an opcode whose whole job is to import a module and call"
                        + " something from it — so Keydra reads the names and stops.");
        return out.toString();
    }

    /** The {@code module name} pairs the stream declares, as they are written in it. */
    private static List<String> imports(byte[] raw) {
        List<String> found = new ArrayList<>();
        for (int i = 2; i < raw.length && found.size() < MAX_IMPORTS; i++) {
            if (raw[i] != GLOBAL) {
                continue;
            }
            // GLOBAL is followed by two newline-terminated lines: module, then name.
            int firstEnd = indexOf(raw, (byte) '\n', i + 1);
            if (firstEnd < 0) {
                break;
            }
            int secondEnd = indexOf(raw, (byte) '\n', firstEnd + 1);
            if (secondEnd < 0) {
                break;
            }
            String module = new String(raw, i + 1, firstEnd - i - 1, StandardCharsets.UTF_8);
            String name =
                    new String(raw, firstEnd + 1, secondEnd - firstEnd - 1, StandardCharsets.UTF_8);
            if (isIdentifier(module) && isIdentifier(name)) {
                found.add(module + "." + name);
                i = secondEnd;
            }
        }
        return found;
    }

    private static int indexOf(byte[] raw, byte needle, int from) {
        for (int i = from; i < raw.length; i++) {
            if (raw[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifier(String candidate) {
        return !candidate.isEmpty()
                && candidate
                        .chars()
                        .allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '.');
    }
}
