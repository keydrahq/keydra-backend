package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Base64;

/**
 * Renders bytes as base64.
 *
 * <p>Like hex, only on request: base64 is a way to look at bytes, not a format they are stored in,
 * and guessing it would misreport ordinary text that happens to use the alphabet.
 */
@ApplicationScoped
public class Base64Decoder implements ValueDecoder {

    public static final String ID = "base64";

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
        return false;
    }

    @Override
    public String decode(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }
}
