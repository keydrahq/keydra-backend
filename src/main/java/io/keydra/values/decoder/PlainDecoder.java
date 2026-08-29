package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;

/** The catch-all: bytes as UTF-8 text. Always applies, and always last. */
@ApplicationScoped
public class PlainDecoder implements ValueDecoder {

    public static final String ID = "plain";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean canDecode(byte[] raw) {
        return true;
    }

    @Override
    public String decode(byte[] raw) {
        return new String(raw, StandardCharsets.UTF_8);
    }
}
