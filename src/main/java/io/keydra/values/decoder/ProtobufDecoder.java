package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;

/**
 * Protocol buffers, rendered as the structure the bytes carry.
 *
 * <p>A protobuf message does not carry its schema: the wire format is field numbers and wire types,
 * and the names come from a .proto file Keydra has never seen. So the rendering is honest about
 * what is there — field 1 is a varint of 42, field 3 is a length-delimited run that happens to read
 * as text — rather than inventing names for it.
 *
 * <p>That is still the difference between "this value is 31 bytes of nothing" and a structure
 * somebody can match against the schema they already know.
 */
@ApplicationScoped
public class ProtobufDecoder implements ValueDecoder {

    public static final String ID = "protobuf";

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_64BIT = 1;
    private static final int WIRE_LENGTH = 2;
    private static final int WIRE_32BIT = 5;

    /** Deeper than this and the rendering is longer than the value it describes. */
    private static final int MAX_DEPTH = 6;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        // Below everything that announces itself: protobuf has no header at all, so this
        // may only claim a value nothing more specific recognised.
        return 40;
    }

    /** Beyond this many fields the "message" is a run of bytes that happens to parse. */
    private static final int MAX_FIELDS = 256;

    @Override
    public boolean canDecode(byte[] raw) {
        /*
         * Anything that reads as text is left to the decoders that can read text. The wire
         * format is bytes people never type — a run of 'x' parses as field 15 repeated a
         * hundred thousand times, and claiming it would turn a plain string into pages of
         * invented structure.
         */
        if (raw.length == 0 || isPrintable(new String(raw, StandardCharsets.UTF_8))) {
            return false;
        }
        // Claimed only when the whole input parses as fields, and there are enough to be a
        // message but few enough to be one: a single varint is indistinguishable from a
        // short string, and a hundred thousand of them are not a message either.
        int fields = new Reader(raw).count();
        return fields > 1 && fields <= MAX_FIELDS;
    }

    @Override
    public String decode(byte[] raw) {
        StringBuilder out = new StringBuilder();
        new Reader(raw).render(out, 0);
        return out.isEmpty() ? new String(raw, StandardCharsets.UTF_8) : out.toString();
    }

    /** Walks the wire format, either counting fields or writing them out. */
    private static final class Reader {
        private final byte[] raw;
        private int at;

        Reader(byte[] raw) {
            this.raw = raw;
        }

        /** How many top-level fields parse, or -1 when the bytes are not a message. */
        int count() {
            at = 0;
            int fields = 0;
            while (at < raw.length) {
                if (!skipField()) {
                    return -1;
                }
                fields++;
            }
            return fields;
        }

        void render(StringBuilder out, int depth) {
            at = 0;
            while (at < raw.length) {
                long tag = varint();
                if (tag < 0) {
                    return;
                }
                int field = (int) (tag >>> 3);
                int wire = (int) (tag & 7);
                out.append("  ".repeat(depth)).append(field).append(": ");
                switch (wire) {
                    case WIRE_VARINT -> out.append(varint()).append('\n');
                    case WIRE_64BIT -> {
                        out.append("<8 bytes>\n");
                        at += 8;
                    }
                    case WIRE_32BIT -> {
                        out.append("<4 bytes>\n");
                        at += 4;
                    }
                    case WIRE_LENGTH -> lengthDelimited(out, depth);
                    default -> {
                        out.append("<unknown wire type ").append(wire).append(">\n");
                        return;
                    }
                }
            }
        }

        /**
         * A length-delimited run, which is a string, some bytes, or a nested message.
         *
         * <p>Which of the three cannot be known without the schema, so the run is shown as text
         * when it reads as text and as a nested message when it parses as one — and as its length
         * when it is neither.
         */
        private void lengthDelimited(StringBuilder out, int depth) {
            long length = varint();
            if (length < 0 || at + length > raw.length) {
                out.append("<truncated>\n");
                at = raw.length;
                return;
            }
            byte[] run = new byte[(int) length];
            System.arraycopy(raw, at, run, 0, (int) length);
            at += (int) length;

            if (depth < MAX_DEPTH && new Reader(run).count() > 1) {
                out.append("{\n");
                new Reader(run).render(out, depth + 1);
                out.append("  ".repeat(depth)).append("}\n");
                return;
            }
            String text = new String(run, StandardCharsets.UTF_8);
            if (isPrintable(text)) {
                out.append('"').append(text).append("\"\n");
            } else {
                out.append('<').append(run.length).append(" bytes>\n");
            }
        }

        /** Advances past one field, answering whether it was one. */
        private boolean skipField() {
            long tag = varint();
            if (tag < 0) {
                return false;
            }
            int field = (int) (tag >>> 3);
            if (field == 0) {
                return false;
            }
            return switch ((int) (tag & 7)) {
                case WIRE_VARINT -> varint() >= 0;
                case WIRE_64BIT -> (at += 8) <= raw.length;
                case WIRE_32BIT -> (at += 4) <= raw.length;
                case WIRE_LENGTH -> {
                    long length = varint();
                    yield length >= 0 && (at += (int) length) <= raw.length;
                }
                default -> false;
            };
        }

        /** The next varint, or -1 when the bytes run out or it is unreasonably long. */
        private long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at >= raw.length) {
                    return -1;
                }
                byte b = raw[at++];
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value < 0 ? -1 : value;
                }
            }
            return -1;
        }
    }

    private static boolean isPrintable(String text) {
        return !text.isEmpty()
                && text.chars().allMatch(c -> c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7f));
    }
}
