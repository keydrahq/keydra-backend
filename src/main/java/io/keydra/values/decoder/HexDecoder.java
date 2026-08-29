package io.keydra.values.decoder;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HexFormat;

/**
 * Renders bytes as hex.
 *
 * <p>Never chosen automatically: any bytes are valid hex, so offering it as a guess would hide the
 * real format. It exists for a client that asks for it explicitly, when a value is binary and the
 * exact bytes matter.
 */
@ApplicationScoped
public class HexDecoder implements ValueDecoder {

    public static final String ID = "hex";

    private static final HexFormat FORMAT = HexFormat.of().withDelimiter(" ");

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
        // Only on request; automatic selection would turn every value into a hex dump.
        return false;
    }

    @Override
    public String decode(byte[] raw) {
        return FORMAT.formatHex(raw);
    }
}
