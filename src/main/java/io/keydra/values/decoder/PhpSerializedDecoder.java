package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;

/**
 * PHP's {@code serialize()} format, which is what a PHP session in Redis usually is.
 *
 * <p>Rendered as indented JSON-like text rather than left as one line: the format nests, and a
 * session object several levels deep is unreadable as the single line PHP writes it.
 *
 * <p>Parsed rather than evaluated. The format can name a class to instantiate ({@code
 * O:8:"stdClass"}) and PHP's own unserialize will construct it; nothing here constructs anything —
 * a named class becomes a labelled object and its properties are read as data.
 */
@ApplicationScoped
public class PhpSerializedDecoder implements ValueDecoder {

    public static final String ID = "php";

    /** How deep the reader will follow a nested value before giving up. */
    private static final int MAX_DEPTH = 64;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        // Above JSON: a PHP string is "s:5:\"hello\";", which JSON would not claim, but
        // being specific about a format that announces itself costs nothing.
        return 15;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < 2) {
            return false;
        }
        // Every value starts with a type letter and a colon, except null, which is "N;".
        char type = (char) raw[0];
        boolean looksRight = "aObdis".indexOf(type) >= 0 && raw[1] == ':';
        if (!looksRight && !(type == 'N' && raw[1] == ';')) {
            return false;
        }
        // Proving it parses is what keeps a two-character prefix from claiming a value.
        return parseWhole(new String(raw, StandardCharsets.UTF_8)) != null;
    }

    @Override
    public String decode(byte[] raw) {
        String rendered = parseWhole(new String(raw, StandardCharsets.UTF_8));
        // Reached only when canDecode already parsed it; fall back rather than throw.
        return rendered == null ? new String(raw, StandardCharsets.UTF_8) : rendered;
    }

    /** Renders the whole input, or null when it is not one complete serialised value. */
    private static String parseWhole(String text) {
        Reader reader = new Reader(text);
        try {
            StringBuilder out = new StringBuilder();
            reader.value(out, 0, 0);
            // Trailing content means this was not one value, which is not this format.
            return reader.atEnd() ? out.toString() : null;
        } catch (RuntimeException notPhp) {
            return null;
        }
    }

    /** A cursor over the serialised text, writing the rendering as it goes. */
    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return at >= text.length();
        }

        void value(StringBuilder out, int depth, int indent) {
            if (depth > MAX_DEPTH) {
                throw new IllegalStateException("nested too deep");
            }
            char type = text.charAt(at);
            switch (type) {
                case 'N' -> {
                    expect("N;");
                    out.append("null");
                }
                case 'b' -> {
                    at += 2;
                    out.append(take(';').equals("1") ? "true" : "false");
                }
                case 'i', 'd' -> {
                    at += 2;
                    out.append(take(';'));
                }
                case 's' -> {
                    at += 2;
                    int length = Integer.parseInt(take(':'));
                    expect("\"");
                    // The declared length is in bytes; the reader works in characters,
                    // which agree for anything the closing quote can be trusted after.
                    String value = text.substring(at, at + length);
                    at += length;
                    expect("\";");
                    out.append('"').append(value.replace("\"", "\\\"")).append('"');
                }
                case 'a' -> array(out, depth, indent, null);
                case 'O' -> {
                    at += 2;
                    int nameLength = Integer.parseInt(take(':'));
                    expect("\"");
                    String className = text.substring(at, at + nameLength);
                    at += nameLength;
                    expect("\":");
                    array(out, depth, indent, className);
                }
                default -> throw new IllegalStateException("not a PHP value: " + type);
            }
        }

        /** An array or an object's properties; both are a count and then pairs. */
        private void array(StringBuilder out, int depth, int indent, String className) {
            if (className == null) {
                at += 2;
            }
            int count = Integer.parseInt(take(':'));
            expect("{");
            out.append(className == null ? "{" : className + " {");
            for (int i = 0; i < count; i++) {
                out.append('\n').append(" ".repeat((indent + 1) * 2));
                value(out, depth + 1, indent + 1);
                out.append(": ");
                value(out, depth + 1, indent + 1);
            }
            expect("}");
            if (count > 0) {
                out.append('\n').append(" ".repeat(indent * 2));
            }
            out.append('}');
        }

        /** Everything up to the next delimiter, which is then consumed. */
        private String take(char delimiter) {
            int end = text.indexOf(delimiter, at);
            if (end < 0) {
                throw new IllegalStateException("unterminated");
            }
            String value = text.substring(at, end);
            at = end + 1;
            return value;
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, at)) {
                throw new IllegalStateException("expected " + literal);
            }
            at += literal.length();
        }
    }
}
