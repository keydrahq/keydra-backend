package io.keydra.values.decoder;

import io.airlift.compress.zstd.ZstdInputStream;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Unwraps zstandard, the compressor most things that compress a cache entry now reach for.
 *
 * <p>Detection is as safe as gzip's: a zstd frame opens with a four-byte magic number that nothing
 * else claims, so this sits beside gzip rather than below it with the guesses.
 *
 * <p>Only the standard frame is read. Zstd also defines skippable frames and dictionary
 * compression; a value written with a dictionary cannot be read without that dictionary, which is
 * somewhere Keydra has no way to reach, so such a value falls through to being shown as bytes
 * rather than being half-rendered.
 */
@ApplicationScoped
public class ZstdDecoder implements ValueDecoder {

    public static final String ID = "zstd";

    /** 0xFD2FB528, little-endian, at the front of every zstd frame. */
    private static final byte[] MAGIC = {(byte) 0x28, (byte) 0xb5, (byte) 0x2f, (byte) 0xfd};

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        // A four-byte magic is as distinctive as gzip's.
        return 10;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        if (raw.length <= MAGIC.length) {
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
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // The magic matched but the frame did not decompress — truncated, or written
            // with a dictionary this cannot have. Show what is there rather than nothing.
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
