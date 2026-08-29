package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Unwraps raw zlib/deflate, the framing HTTP calls {@code deflate}.
 *
 * <p>Detection is weaker than gzip's: zlib has a two-byte header rather than a magic number, and
 * the check below — a deflate compression method with a valid header checksum — can in principle
 * match arbitrary bytes. It is therefore given a lower priority than gzip and msgpack, and it only
 * claims a value it can actually inflate, so a false positive costs one failed inflate rather than
 * a wrong rendering.
 */
@ApplicationScoped
public class DeflateDecoder implements ValueDecoder {

    public static final String ID = "deflate";

    /** Low nibble of the first byte: 8 is the only compression method zlib defines. */
    private static final int DEFLATE_METHOD = 8;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        // Below gzip and msgpack: its header is the least distinctive of the three.
        return 30;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < 2 || (raw[0] & 0x0f) != DEFLATE_METHOD) {
            return false;
        }
        // The zlib header is valid when the two bytes together are a multiple of 31.
        int header = ((raw[0] & 0xff) << 8) | (raw[1] & 0xff);
        if (header % 31 != 0) {
            return false;
        }
        // Proving it inflates is what makes the weak header check safe to act on.
        return inflate(raw) != null;
    }

    @Override
    public String decode(byte[] raw) {
        byte[] inflated = inflate(raw);
        // Reached only when canDecode already inflated it; fall back rather than throw.
        return new String(inflated == null ? raw : inflated, StandardCharsets.UTF_8);
    }

    /** Returns the inflated bytes, or null when the input is not zlib after all. */
    private static byte[] inflate(byte[] raw) {
        Inflater inflater = new Inflater();
        inflater.setInput(raw);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length * 2);
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(chunk);
                if (read == 0) {
                    // Needs more input than there is: truncated or not deflate at all.
                    return inflater.finished() ? out.toByteArray() : null;
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
