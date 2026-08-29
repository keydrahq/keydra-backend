package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.brotli.dec.BrotliInputStream;

/**
 * Unwraps brotli, which announces itself in no way at all.
 *
 * <p>Every other compressor here opens with a magic number. Brotli's stream begins with a window
 * size packed into the first few bits, which is to say it begins with almost any byte — so there is
 * nothing to recognise, and "it decompressed" is not the proof it is for zlib, whose checksum has
 * to come out right at the end. A brotli stream carries no such check.
 *
 * <p>So the guess is fenced instead. It is the last thing tried before falling back to text; the
 * stored value has to look like bytes rather than something already readable; what comes out has to
 * be valid, printable UTF-8; and it has to be longer than what went in, because a compressor that
 * made a value bigger is not the compressor that wrote it. A value that clears all four is
 * overwhelmingly the thing it looks like, and one that does not is shown as what it is.
 */
@ApplicationScoped
public class BrotliDecoder implements ValueDecoder {

    public static final String ID = "brotli";

    /** Below protobuf: that one at least validates a structure, and this one cannot. */
    private static final int PRIORITY = 50;

    /** Nothing shorter than this is a brotli stream worth claiming. */
    private static final int MIN_LENGTH = 4;

    /** A decompressed value larger than this is not rendered anyway; stop reading there. */
    private static final int MAX_DECOMPRESSED = 8 * 1024 * 1024;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length < MIN_LENGTH || isReadableText(raw)) {
            return false;
        }
        byte[] decompressed = decompress(raw);
        return decompressed != null
                && decompressed.length > raw.length
                && isReadableText(decompressed);
    }

    @Override
    public String decode(byte[] raw) {
        byte[] decompressed = decompress(raw);
        // Reached only when canDecode already decompressed it; fall back rather than throw.
        return new String(decompressed == null ? raw : decompressed, StandardCharsets.UTF_8);
    }

    /** Returns the decompressed bytes, or null when the input is not brotli after all. */
    private static byte[] decompress(byte[] raw) {
        try (BrotliInputStream in = new BrotliInputStream(new ByteArrayInputStream(raw))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length * 4);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
                if (out.size() > MAX_DECOMPRESSED) {
                    return null;
                }
            }
            return out.size() == 0 ? null : out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whether these bytes are text somebody could read.
     *
     * <p>Used at both ends of the guess and meaning the opposite thing each time: the stored value
     * must fail this — readable text is not something a compressor produced — and what came out of
     * it must pass, because a wrong guess decompresses to noise.
     */
    private static boolean isReadableText(byte[] bytes) {
        CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        return text.chars()
                .noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }
}
