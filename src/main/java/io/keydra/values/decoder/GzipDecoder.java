package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Unwraps gzip, so a compressed cache entry reads as its contents rather than as noise. */
@ApplicationScoped
public class GzipDecoder implements ValueDecoder {

    public static final String ID = "gzip";

    private static final int MAGIC_0 = 0x1f;
    private static final int MAGIC_1 = 0x8b;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        return raw.length > 2 && (raw[0] & 0xff) == MAGIC_0 && (raw[1] & 0xff) == MAGIC_1;
    }

    @Override
    public String decode(byte[] raw) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The magic bytes matched but the body did not; show what is there.
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
