package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.jpountz.lz4.LZ4FrameInputStream;

/**
 * Unwraps LZ4, in the framing that carries its own length.
 *
 * <p>LZ4 comes in two shapes and only one of them can be recognised. A frame opens with a four-byte
 * magic number and says how long its contents are; a bare block is just compressed bytes, with the
 * uncompressed length kept by whoever stored it. There is nothing to detect in the second and
 * nothing to decompress it into, so this reads frames and lets a bare block fall through to being
 * shown as bytes — which is the honest answer rather than a guess at a length.
 */
@ApplicationScoped
public class Lz4Decoder implements ValueDecoder {

    public static final String ID = "lz4";

    /** 0x184D2204, little-endian, at the front of every LZ4 frame. */
    private static final byte[] MAGIC = {(byte) 0x04, (byte) 0x22, (byte) 0x4d, (byte) 0x18};

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
        try (LZ4FrameInputStream in = new LZ4FrameInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // The magic matched but the frame did not hold together; show the bytes.
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
