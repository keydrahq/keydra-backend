package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Java's serialization stream — identified and described, never deserialized.
 *
 * <p>Deserializing a Java stream constructs whatever classes it names, running their readObject as
 * it goes; a value read out of somebody's Redis is exactly the untrusted input that turns into
 * remote code execution. So this decoder reads the class names out of the header and stops: it says
 * what the value is and what it contains, which is what somebody browsing needs, and it never hands
 * the bytes to an ObjectInputStream.
 *
 * <p>That is a decision rather than a limitation, and the rendering says so — a reader who sees
 * only class names should know the value was not opened rather than assume it was empty.
 */
@ApplicationScoped
public class JavaSerializedDecoder implements ValueDecoder {

    public static final String ID = "java";

    /** Every Java serialization stream starts with this magic and version. */
    private static final byte[] MAGIC = {(byte) 0xac, (byte) 0xed, 0x00, 0x05};

    /** TC_CLASSDESC: a class description follows, starting with its name as a UTF string. */
    private static final byte CLASS_DESC = 0x72;

    /** How many class names are worth listing before the list stops being a summary. */
    private static final int MAX_CLASSES = 32;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        // A four-byte magic is as distinctive as gzip's, and nothing else claims it.
        return 10;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String decode(byte[] raw) {
        List<String> classes = classNames(raw);
        StringBuilder out = new StringBuilder("Java serialized object, ");
        out.append(raw.length).append(" bytes\n\n");
        if (classes.isEmpty()) {
            out.append("No class names were readable from the stream header.\n");
        } else {
            out.append("Classes named in the stream:\n");
            classes.forEach(name -> out.append("  ").append(name).append('\n'));
        }
        out.append(
                "\n"
                    + "The value was not deserialized. Reading a Java stream constructs the classes"
                    + " it names, which is how an untrusted value becomes code that runs — so"
                    + " Keydra reads the names and stops.");
        return out.toString();
    }

    /**
     * The class names the stream declares.
     *
     * <p>Found by scanning for the class-description marker rather than by parsing the stream: a
     * full parse would have to model the whole grammar, and the names are the part worth showing. A
     * byte that happens to look like a marker yields an unreadable name and is skipped.
     */
    private static List<String> classNames(byte[] raw) {
        List<String> names = new ArrayList<>();
        for (int i = MAGIC.length; i + 3 < raw.length && names.size() < MAX_CLASSES; i++) {
            if (raw[i] != CLASS_DESC) {
                continue;
            }
            int length = ((raw[i + 1] & 0xff) << 8) | (raw[i + 2] & 0xff);
            if (length <= 0 || i + 3 + length > raw.length) {
                continue;
            }
            String name = new String(raw, i + 3, length, StandardCharsets.UTF_8);
            if (isClassName(name)) {
                names.add(name);
                i += 2 + length;
            }
        }
        return names;
    }

    /** Whether a run of bytes reads as a class name rather than as whatever followed a marker. */
    private static boolean isClassName(String candidate) {
        if (candidate.isEmpty() || !Character.isJavaIdentifierStart(candidate.charAt(0))) {
            return false;
        }
        return candidate
                .chars()
                .allMatch(
                        c -> Character.isJavaIdentifierPart(c) || c == '.' || c == '$' || c == '[');
    }
}
